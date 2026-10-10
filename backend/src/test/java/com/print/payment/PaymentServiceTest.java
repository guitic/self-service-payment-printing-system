package com.print.payment;

import com.print.file.entity.PrintFile;
import com.print.order.OrderStatus;
import com.print.order.entity.PrintOrder;
import com.print.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 支付服务集成测试：支付确认幂等（不重复投递任务）、金额校验、发起支付（CONTRACT §5 / TC-PAY）。
 */
class PaymentServiceTest extends AbstractIntegrationTest {

    @Autowired
    private PaymentService paymentService;

    @Test
    @DisplayName("TC-PAY-04 markPaidAndEnqueue 重复确认只生效一次，且仅投递 1 个打印任务")
    void markPaidAndEnqueueIdempotent() {
        PrintFile f = newFile(1L, 5);
        PrintOrder o = newOrder(1L, f.getId(), new BigDecimal("2.00"), OrderStatus.PAY_PENDING);
        String tradeNo = "MOCK" + o.getOrderNo();

        boolean first = paymentService.markPaidAndEnqueue(o.getOrderNo(), "mock", tradeNo, "2.00", true);
        boolean second = paymentService.markPaidAndEnqueue(o.getOrderNo(), "mock", tradeNo, "2.00", true);

        assertThat(first).isTrue();
        assertThat(second).isFalse();
        assertThat(countTasks(o.getOrderNo())).isEqualTo(1);
        assertThat(orderMapper.selectById(o.getId()).getPayStatus()).isEqualTo(OrderStatus.PAY_PAID);
    }

    @Test
    @DisplayName("TC-PAY-05 回调金额与订单不一致 -> 拒绝，订单不变、不投递任务")
    void amountMismatchRejected() {
        PrintFile f = newFile(1L, 5);
        PrintOrder o = newOrder(1L, f.getId(), new BigDecimal("2.00"), OrderStatus.PAY_PENDING);

        boolean ok = paymentService.markPaidAndEnqueue(o.getOrderNo(), "mock", "T1", "0.01", true);

        assertThat(ok).isFalse();
        assertThat(countTasks(o.getOrderNo())).isZero();
        assertThat(orderMapper.selectById(o.getId()).getPayStatus()).isEqualTo(OrderStatus.PAY_PENDING);
    }

    @Test
    @DisplayName("TC-PAY-02 Mock 确认成功 -> 已支付 + 投递任务；重复确认不重复投递")
    void mockConfirmSuccessCreatesOneTask() {
        PrintFile f = newFile(1L, 5);
        PrintOrder o = newOrder(1L, f.getId(), new BigDecimal("2.00"), OrderStatus.PAY_PENDING);

        var vo = paymentService.mockConfirm(o.getOrderNo(), "SUCCESS", 1L);
        assertThat(vo.payStatus()).isEqualTo(OrderStatus.PAY_PAID);
        assertThat(countTasks(o.getOrderNo())).isEqualTo(1);

        // 再次确认（幂等）：仍只有 1 个任务
        paymentService.mockConfirm(o.getOrderNo(), "SUCCESS", 1L);
        assertThat(countTasks(o.getOrderNo())).isEqualTo(1);
    }

    @Test
    @DisplayName("Mock 确认 FAIL -> 保持待支付，不投递任务")
    void mockConfirmFailKeepsPending() {
        PrintFile f = newFile(1L, 5);
        PrintOrder o = newOrder(1L, f.getId(), new BigDecimal("2.00"), OrderStatus.PAY_PENDING);

        var vo = paymentService.mockConfirm(o.getOrderNo(), "FAIL", 1L);

        assertThat(vo.payStatus()).isEqualTo(OrderStatus.PAY_PENDING);
        assertThat(countTasks(o.getOrderNo())).isZero();
    }

    @Test
    @DisplayName("TC-PAY-01 发起支付返回可解析二维码内容")
    void prepayReturnsParseableQr() {
        PrintFile f = newFile(1L, 5);
        PrintOrder o = newOrder(1L, f.getId(), new BigDecimal("2.00"), OrderStatus.PAY_PENDING);

        var vo = paymentService.prepay(o.getOrderNo(), 1L);

        assertThat(vo.gateway()).isEqualTo("mock");
        assertThat(vo.qrCodeContent()).isEqualTo("mockpay://order/" + o.getOrderNo() + "?amount=2.00");
    }
}
