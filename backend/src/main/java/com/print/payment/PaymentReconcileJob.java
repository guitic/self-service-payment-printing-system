package com.print.payment;

import com.print.order.OrderService;
import com.print.order.entity.PrintOrder;
import com.print.payment.gateway.PaymentGateway;
import com.print.payment.gateway.PaymentGatewayFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 支付对账补偿任务（设计文档 §3.3.2 / 风险 OPS-3）。
 *
 * <p>回调丢失是支付链路的经典故障：用户已扣款但本地仍是「待支付」，
 * 若不补偿，订单会被 {@code closeExpiredOrders()} 关闭，造成「已付费未出纸」。
 * 本任务周期性扫描「已过期仍待支付」的订单，主动向网关查询，命中成功即补记并投递打印任务。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentReconcileJob {

    private static final int BATCH = 50;

    private final OrderService orderService;
    private final PaymentService paymentService;
    private final PaymentGatewayFactory gatewayFactory;

    /** 每 60s 补偿一次。 */
    @Scheduled(initialDelay = 60_000, fixedDelay = 60_000)
    public void reconcilePendingPayments() {
        List<PrintOrder> pendings;
        try {
            pendings = orderService.findExpiredPendingOrders(BATCH);
        } catch (Exception e) {
            log.error("支付对账：查询待支付订单失败", e);
            return;
        }
        if (pendings.isEmpty()) {
            return;
        }
        PaymentGateway gateway;
        try {
            gateway = gatewayFactory.current();
        } catch (Exception e) {
            log.warn("支付对账：当前网关不可用，跳过本轮: {}", e.getMessage());
            return;
        }
        for (PrintOrder o : pendings) {
            try {
                PaymentGateway.PayQueryResult q = gateway.query(o.getOrderNo());
                if (q != null && q.state() == PaymentGateway.PayState.SUCCESS) {
                    log.warn("支付对账补偿：网关已成功但本地待支付，补记: orderNo={}", o.getOrderNo());
                    paymentService.markPaidAndEnqueue(o.getOrderNo(), gateway.getGatewayCode(),
                            q.tradeNo(), null, false);
                }
            } catch (Exception e) {
                log.warn("支付对账：查询失败 orderNo={}, {}", o.getOrderNo(), e.getMessage());
            }
        }
    }
}
