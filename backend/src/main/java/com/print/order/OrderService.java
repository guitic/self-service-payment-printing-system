package com.print.order;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.print.common.BizException;
import com.print.common.PageResult;
import com.print.common.ResultCode;
import com.print.config.PrintProperties;
import com.print.file.FileService;
import com.print.file.entity.PrintFile;
import com.print.order.entity.PrintOrder;
import com.print.order.mapper.PrintOrderMapper;
import com.print.pricing.PricingService;
import com.print.task.TerminalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 订单服务：创建、查询、取消、支付状态迁移（CAS 幂等）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private static final DateTimeFormatter NO_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final PrintOrderMapper orderMapper;
    private final FileService fileService;
    private final PricingService pricingService;
    private final TerminalService terminalService;
    private final PrintProperties props;
    private final ObjectMapper objectMapper;

    public record OrderVO(String orderNo, Long fileId, String fileName, Integer pageCount,
                          java.math.BigDecimal amount, Integer payStatus, String payStatusText,
                          Integer printStatus, String printStatusText, Object params,
                          LocalDateTime expireAt, LocalDateTime createdAt) {
    }

    /** 创建订单：计价快照 + 生成订单号。 */
    public OrderVO create(PricingService.QuoteReq req, String terminalCode, Long userId) {
        PricingService.QuoteResult quote = pricingService.quote(req, userId);
        PrintFile file = fileService.requireOwned(req.fileId(), userId);
        Long terminalId = terminalService.resolveTerminalId(terminalCode);

        LocalDateTime now = LocalDateTime.now();
        PrintOrder o = new PrintOrder();
        o.setOrderNo(genOrderNo());
        o.setUserId(userId);
        o.setFileId(file.getId());
        o.setTerminalId(terminalId);
        o.setParamsJson(toJson(req));
        o.setPageCount(quote.billablePages());
        o.setAmount(quote.amount());
        o.setPayStatus(OrderStatus.PAY_PENDING);
        o.setPrintStatus(OrderStatus.PRINT_PENDING);
        o.setPriceRuleId(quote.ruleId());
        o.setExpireAt(now.plusMinutes(props.getOrder().getPayExpireMinutes()));
        o.setCreatedAt(now);
        o.setUpdatedAt(now);
        insertOrderWithRetry(o);

        log.info("订单创建: orderNo={}, amount={}, fileId={}", o.getOrderNo(), o.getAmount(), file.getId());
        return toVO(o);
    }

    /**
     * 插入订单，订单号碰撞时重试。
     *
     * <p>压测发现：秒级时间戳 + 6 位随机在约 600 单/秒下会因生日悖论碰撞
     * （唯一索引报错 → HTTP 500）。这里将随机位扩到 8 位并做有限重试，
     * MySQL/H2 在唯一键冲突后事务仍可用，故可直接重试。
     */
    private void insertOrderWithRetry(PrintOrder o) {
        for (int attempt = 1; attempt <= 3; attempt++) {
            o.setOrderNo(genOrderNo());
            try {
                orderMapper.insert(o);
                return;
            } catch (DuplicateKeyException e) {
                log.warn("订单号碰撞，重试第 {} 次: {}", attempt, o.getOrderNo());
            }
        }
        throw new BizException(500, "订单号生成失败，请重试");
    }

    public PrintOrder requireOwned(String orderNo, Long userId) {
        PrintOrder o = orderMapper.selectOne(new LambdaQueryWrapper<PrintOrder>()
                .eq(PrintOrder::getOrderNo, orderNo));
        if (o == null) {
            throw new BizException(ResultCode.ORDER_NOT_FOUND);
        }
        if (userId == null || !Objects.equals(o.getUserId(), userId)) {
            throw new BizException(ResultCode.FORBIDDEN);
        }
        return o;
    }

    public PrintOrder findByOrderNo(String orderNo) {
        PrintOrder o = orderMapper.selectOne(new LambdaQueryWrapper<PrintOrder>()
                .eq(PrintOrder::getOrderNo, orderNo));
        if (o == null) {
            throw new BizException(ResultCode.ORDER_NOT_FOUND);
        }
        return o;
    }

    public OrderVO detail(String orderNo, Long userId) {
        return toVO(requireOwned(orderNo, userId));
    }

    public PageResult<OrderVO> listByUser(Long userId, long page, long size) {
        Page<PrintOrder> p = orderMapper.selectPage(new Page<>(page, size),
                new LambdaQueryWrapper<PrintOrder>()
                        .eq(PrintOrder::getUserId, userId)
                        .orderByDesc(PrintOrder::getId));
        List<OrderVO> list = p.getRecords().stream().map(this::toVO).toList();
        return PageResult.of(list, p.getTotal(), page, size);
    }

    /** 用户取消未支付订单。 */
    public void cancel(String orderNo, Long userId) {
        PrintOrder o = requireOwned(orderNo, userId);
        if (Objects.equals(o.getPayStatus(), OrderStatus.PAY_PAID)) {
            throw new BizException(ResultCode.ORDER_ALREADY_PAID);
        }
        if (!Objects.equals(o.getPayStatus(), OrderStatus.PAY_PENDING)) {
            throw new BizException(ResultCode.ORDER_CLOSED);
        }
        boolean ok = casPayStatus(orderNo, OrderStatus.PAY_PENDING, OrderStatus.PAY_CLOSED);
        if (!ok) {
            throw new BizException(ResultCode.ORDER_CLOSED);
        }
    }

    /**
     * 支付成功：CAS {@code 待支付 -> 已支付}，仅一次生效（幂等）。
     *
     * @return true 表示本次生效（首次）
     */
    public boolean markPaid(String orderNo) {
        return casPayStatus(orderNo, OrderStatus.PAY_PENDING, OrderStatus.PAY_PAID);
    }

    /** 条件更新支付状态（幂等核心）。 */
    public boolean casPayStatus(String orderNo, int from, int to) {
        int rows = orderMapper.update(null, new LambdaUpdateWrapper<PrintOrder>()
                .eq(PrintOrder::getOrderNo, orderNo)
                .eq(PrintOrder::getPayStatus, from)
                .set(PrintOrder::getPayStatus, to)
                .set(PrintOrder::getUpdatedAt, LocalDateTime.now()));
        return rows == 1;
    }

    public void updatePrintStatus(String orderNo, int printStatus) {
        orderMapper.update(null, new LambdaUpdateWrapper<PrintOrder>()
                .eq(PrintOrder::getOrderNo, orderNo)
                .set(PrintOrder::getPrintStatus, printStatus)
                .set(PrintOrder::getUpdatedAt, LocalDateTime.now()));
    }

    /** 超时未支付订单批量关闭（定时任务调用）。 */
    public int closeExpiredOrders() {
        int rows = orderMapper.update(null, new LambdaUpdateWrapper<PrintOrder>()
                .eq(PrintOrder::getPayStatus, OrderStatus.PAY_PENDING)
                .lt(PrintOrder::getExpireAt, LocalDateTime.now())
                .set(PrintOrder::getPayStatus, OrderStatus.PAY_CLOSED)
                .set(PrintOrder::getUpdatedAt, LocalDateTime.now()));
        if (rows > 0) {
            log.info("超时关闭订单 {} 笔", rows);
        }
        return rows;
    }

    /** 已过期但仍「待支付」的订单（供支付对账补偿任务主动向网关查询）。 */
    public List<PrintOrder> findExpiredPendingOrders(int limit) {
        return orderMapper.selectList(new LambdaQueryWrapper<PrintOrder>()
                .eq(PrintOrder::getPayStatus, OrderStatus.PAY_PENDING)
                .lt(PrintOrder::getExpireAt, LocalDateTime.now())
                .orderByAsc(PrintOrder::getId)
                .last("LIMIT " + Math.max(1, limit)));
    }

    public OrderVO toVO(PrintOrder o) {
        String fileName = null;
        PrintFile f = fileService.findMeta(o.getFileId());
        if (f != null) {
            fileName = f.getOriginalName();
        }
        return new OrderVO(o.getOrderNo(), o.getFileId(), fileName, o.getPageCount(), o.getAmount(),
                o.getPayStatus(), OrderStatus.payText(o.getPayStatus()),
                o.getPrintStatus(), OrderStatus.printText(o.getPrintStatus()),
                parseJson(o.getParamsJson()), o.getExpireAt(), o.getCreatedAt());
    }

    private Object parseJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return json;
        }
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String genOrderNo() {
        // 秒级时间戳 + 8 位随机：单机约 1 亿/秒的组合空间，本场景碰撞概率可忽略
        return "P" + LocalDateTime.now().format(NO_FMT)
                + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
    }
}
