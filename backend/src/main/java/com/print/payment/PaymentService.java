package com.print.payment;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.print.common.BizException;
import com.print.common.ResultCode;
import com.print.order.OrderService;
import com.print.order.OrderStatus;
import com.print.order.entity.PrintOrder;
import com.print.payment.entity.PaymentRecord;
import com.print.payment.gateway.MockPaymentGateway;
import com.print.payment.gateway.PaymentGateway;
import com.print.payment.gateway.PaymentGatewayFactory;
import com.print.payment.mapper.PaymentRecordMapper;
import com.print.task.TaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;

/**
 * 支付服务：发起支付、状态查询、回调处理、退款。
 *
 * <p>一致性（CONTRACT.md §5）：以 orderNo 为业务幂等键，支付状态用 CAS 条件更新，
 * 重复回调 / 重复确认只生效一次且不重复投递打印任务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentGatewayFactory gatewayFactory;
    private final PaymentRecordMapper recordMapper;
    private final OrderService orderService;
    private final TaskService taskService;
    private final ObjectMapper objectMapper;

    public record PrepayVO(String orderNo, String gateway, String tradeNo, String qrCodeContent,
                           BigDecimal amount, LocalDateTime expireAt) {
    }

    public record PayStatusVO(String orderNo, Integer payStatus, String payStatusText) {
    }

    /** 发起支付，返回收款二维码内容。 */
    @Transactional
    public PrepayVO prepay(String orderNo, Long userId) {
        PrintOrder order = orderService.requireOwned(orderNo, userId);
        if (!Objects.equals(order.getPayStatus(), OrderStatus.PAY_PENDING)) {
            throw new BizException(ResultCode.ORDER_CLOSED.code(), "订单当前状态不可发起支付");
        }
        PaymentGateway gateway = gatewayFactory.current();
        PaymentGateway.PrepayResult result = gateway.prepay(new PaymentGateway.PrepayRequest(
                orderNo, order.getAmount(), "打印订单 " + orderNo, order.getExpireAt()));

        LocalDateTime now = LocalDateTime.now();
        PaymentRecord rec = recordMapper.selectOne(new LambdaQueryWrapper<PaymentRecord>()
                .eq(PaymentRecord::getOrderNo, orderNo));
        if (rec == null) {
            rec = new PaymentRecord();
            rec.setOrderNo(orderNo);
            rec.setGateway(result.gateway());
            rec.setTradeNo(result.tradeNo());
            rec.setAmount(order.getAmount());
            rec.setStatus(0);
            rec.setCreatedAt(now);
            rec.setUpdatedAt(now);
            recordMapper.insert(rec);
        } else {
            rec.setGateway(result.gateway());
            rec.setTradeNo(result.tradeNo());
            rec.setUpdatedAt(now);
            recordMapper.updateById(rec);
        }
        return new PrepayVO(orderNo, result.gateway(), result.tradeNo(),
                result.qrCodeContent(), order.getAmount(), result.expireAt());
    }

    /**
     * 查询支付状态（前端轮询）。
     *
     * <p>回调丢失兜底（设计 §3.3.1）：本地仍为「待支付」时主动向网关查询，
     * 命中成功即就地补记并投递打印任务，避免「用户已扣款却因回调丢失被超时关单」。
     */
    public PayStatusVO status(String orderNo, Long userId) {
        PrintOrder o = orderService.requireOwned(orderNo, userId);
        if (Objects.equals(o.getPayStatus(), OrderStatus.PAY_PENDING)) {
            try {
                PaymentGateway gateway = gatewayFactory.current();
                PaymentGateway.PayQueryResult q = gateway.query(orderNo);
                if (q != null && q.state() == PaymentGateway.PayState.SUCCESS) {
                    log.warn("支付状态兜底命中（回调可能丢失）: orderNo={}", orderNo);
                    markPaidAndEnqueue(orderNo, gateway.getGatewayCode(), q.tradeNo(), null, false);
                    o = orderService.findByOrderNo(orderNo);
                }
            } catch (Exception e) {
                log.warn("支付状态兜底查询失败: orderNo={}, {}", orderNo, e.getMessage());
            }
        }
        return new PayStatusVO(orderNo, o.getPayStatus(), OrderStatus.payText(o.getPayStatus()));
    }

    /** 【仅 Mock】模拟支付结果：SUCCESS（默认）/ FAIL。调用者须为该订单的归属用户。 */
    @Transactional
    public PayStatusVO mockConfirm(String orderNo, String result, Long userId) {
        if (!gatewayFactory.isMock()) {
            throw new BizException(ResultCode.PAYMENT_GATEWAY_ERROR.code(), "当前非 Mock 网关，禁止调用该接口");
        }
        // 归属校验：防止任意登录用户把他人订单标记为已支付
        PrintOrder order = orderService.requireOwned(orderNo, userId);

        PaymentGateway gateway = gatewayFactory.get("mock");
        boolean success = !"FAIL".equalsIgnoreCase(result);
        String tradeNo = "MOCK" + orderNo;
        if (gateway instanceof MockPaymentGateway mock) {
            mock.confirm(orderNo, success);
            tradeNo = mock.tradeNoOf(orderNo);
        }
        if (success) {
            markPaidAndEnqueue(orderNo, "mock", tradeNo, MockPaymentGateway.amountOf(order.getAmount()), false);
        }
        PrintOrder latest = orderService.findByOrderNo(orderNo);
        return new PayStatusVO(orderNo, latest.getPayStatus(), OrderStatus.payText(latest.getPayStatus()));
    }

    /** 处理网关异步回调（验签 + 幂等 + 金额校验）。 */
    @Transactional
    public boolean handleNotify(String gatewayCode, String rawBody, Map<String, String> headers) {
        PaymentGateway gateway = gatewayFactory.get(gatewayCode);
        PaymentGateway.NotifyResult result = gateway.handleNotify(
                new PaymentGateway.NotifyRequest(gatewayCode, rawBody, headers));
        saveRawNotify(result.orderNo(), rawBody);
        if (!result.success()) {
            log.warn("支付回调处理失败（验签未通过或报文异常）: gateway={}", gatewayCode);
            return false;
        }
        // 优先使用网关解析出的金额（真实渠道金额嵌套在报文内部）；缺失时回退解析根字段
        String amount = result.amount() != null ? result.amount() : extractAmount(rawBody);
        return markPaidAndEnqueue(result.orderNo(), gatewayCode, result.tradeNo(), amount, true);
    }

    /** 取该渠道期望的回调应答（支付宝要求纯文本 success，微信 V3 要求 JSON）。 */
    public PaymentGateway.NotifyAck ackOf(String gatewayCode, boolean success) {
        try {
            return gatewayFactory.get(gatewayCode).ack(success);
        } catch (Exception e) {
            return new PaymentGateway.NotifyAck(success ? 200 : 500,
                    "text/plain;charset=UTF-8", success ? "success" : "failure");
        }
    }

    /**
     * 支付成功落库 + 投递打印任务（幂等核心）。
     *
     * @param requireAmount 是否强制要求金额做校验。回调路径必须为 {@code true}——
     *                      金额缺失即拒绝，避免「解析不到金额就跳过校验」被绕过；
     *                      服务端主动查询（无金额字段）可传 {@code false}
     * @return true 表示本次首次生效
     */
    @Transactional
    public boolean markPaidAndEnqueue(String orderNo, String gateway, String tradeNo,
                                      String amount, boolean requireAmount) {
        PrintOrder order = orderService.findByOrderNo(orderNo);
        if (requireAmount && amount == null) {
            log.error("回调未提供金额，无法做金额强校验，拒绝: orderNo={}, gateway={}", orderNo, gateway);
            return false;
        }
        if (amount != null) {
            try {
                if (order.getAmount().compareTo(new BigDecimal(amount)) != 0) {
                    log.error("支付金额与订单不一致，拒绝: orderNo={}, 订单={}, 回调={}",
                            orderNo, order.getAmount(), amount);
                    return false;
                }
            } catch (NumberFormatException e) {
                log.error("回调金额格式非法: orderNo={}, amount={}", orderNo, amount);
                return false;
            }
        }
        // CAS 待支付 -> 已支付，仅一次生效
        boolean first = orderService.markPaid(orderNo);
        if (!first) {
            log.info("重复支付确认，忽略（不重复投递任务）: orderNo={}", orderNo);
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        recordMapper.update(null, new LambdaUpdateWrapper<PaymentRecord>()
                .eq(PaymentRecord::getOrderNo, orderNo)
                .set(PaymentRecord::getStatus, 1)
                .set(PaymentRecord::getTradeNo, tradeNo)
                .set(PaymentRecord::getPayTime, now)
                .set(PaymentRecord::getUpdatedAt, now));

        taskService.enqueue(orderNo, order.getTerminalId());
        log.info("支付成功: orderNo={}, gateway={}, tradeNo={}", orderNo, gateway, tradeNo);
        return true;
    }

    /** 退款（打印失败 / 取消场景）。 */
    @Transactional
    public void refund(String orderNo, String reason) {
        PrintOrder order = orderService.findByOrderNo(orderNo);
        PaymentGateway gateway = gatewayFactory.current();
        PaymentGateway.RefundResult result = gateway.refund(
                new PaymentGateway.RefundRequest(orderNo, order.getAmount(), reason));
        if (!result.success()) {
            throw new BizException(ResultCode.PAYMENT_GATEWAY_ERROR);
        }
        orderService.casPayStatus(orderNo, OrderStatus.PAY_PAID, OrderStatus.PAY_REFUNDED);
        LocalDateTime now = LocalDateTime.now();
        recordMapper.update(null, new LambdaUpdateWrapper<PaymentRecord>()
                .eq(PaymentRecord::getOrderNo, orderNo)
                .set(PaymentRecord::getStatus, 3)
                .set(PaymentRecord::getUpdatedAt, now));
        log.info("退款完成: orderNo={}, reason={}", orderNo, reason);
    }

    private void saveRawNotify(String orderNo, String rawBody) {
        if (orderNo == null) {
            return;
        }
        try {
            recordMapper.update(null, new LambdaUpdateWrapper<PaymentRecord>()
                    .eq(PaymentRecord::getOrderNo, orderNo)
                    .set(PaymentRecord::getRawNotify, rawBody)
                    .set(PaymentRecord::getUpdatedAt, LocalDateTime.now()));
        } catch (Exception e) {
            log.warn("保存回调原始报文失败: {}", e.getMessage());
        }
    }

    private String extractAmount(String rawBody) {
        try {
            JsonNode node = objectMapper.readTree(rawBody);
            JsonNode amount = node.get("amount");
            return amount == null || amount.isNull() ? null : amount.asText();
        } catch (Exception e) {
            return null;
        }
    }
}
