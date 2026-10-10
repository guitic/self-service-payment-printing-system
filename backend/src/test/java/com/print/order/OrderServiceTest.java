package com.print.order;

import com.print.common.BizException;
import com.print.common.ResultCode;
import com.print.file.entity.PrintFile;
import com.print.order.entity.PrintOrder;
import com.print.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 订单服务集成测试：支付状态 CAS 幂等、取消规则、超时关闭（CONTRACT §5）。
 */
class OrderServiceTest extends AbstractIntegrationTest {

    @Autowired
    private OrderService orderService;

    @Test
    @DisplayName("markPaid 对同一订单只生效一次（第二次返回 false）")
    void markPaidIsIdempotent() {
        PrintFile f = newFile(1L, 5);
        PrintOrder o = newOrder(1L, f.getId(), new BigDecimal("1.00"), OrderStatus.PAY_PENDING);

        assertThat(orderService.markPaid(o.getOrderNo())).isTrue();
        assertThat(orderService.markPaid(o.getOrderNo())).isFalse();

        assertThat(orderMapper.selectById(o.getId()).getPayStatus())
                .isEqualTo(OrderStatus.PAY_PAID);
    }

    @Test
    @DisplayName("CAS 起始状态不匹配时不生效")
    void casWrongFromStateNoop() {
        PrintFile f = newFile(1L, 5);
        PrintOrder o = newOrder(1L, f.getId(), new BigDecimal("1.00"), OrderStatus.PAY_PENDING);

        // 期望从「已支付」迁到「已退款」，但当前是待支付 -> 不生效
        assertThat(orderService.casPayStatus(o.getOrderNo(), OrderStatus.PAY_PAID, OrderStatus.PAY_REFUNDED))
                .isFalse();
        assertThat(orderMapper.selectById(o.getId()).getPayStatus())
                .isEqualTo(OrderStatus.PAY_PENDING);
    }

    @Test
    @DisplayName("取消未支付订单 -> 已关闭")
    void cancelPendingOrder() {
        PrintFile f = newFile(1L, 5);
        PrintOrder o = newOrder(1L, f.getId(), new BigDecimal("1.00"), OrderStatus.PAY_PENDING);

        orderService.cancel(o.getOrderNo(), 1L);

        assertThat(orderMapper.selectById(o.getId()).getPayStatus())
                .isEqualTo(OrderStatus.PAY_CLOSED);
    }

    @Test
    @DisplayName("已支付订单不可取消 -> 4002")
    void cancelPaidOrderRejected() {
        PrintFile f = newFile(1L, 5);
        PrintOrder o = newOrder(1L, f.getId(), new BigDecimal("1.00"), OrderStatus.PAY_PAID);

        BizException ex = catchThrowableOfType(
                () -> orderService.cancel(o.getOrderNo(), 1L), BizException.class);
        assertThat(ex).isNotNull();
        assertThat(ex.getCode()).isEqualTo(ResultCode.ORDER_ALREADY_PAID.code());
    }

    @Test
    @DisplayName("越权访问他人订单 -> 403")
    void requireOwnedForbidden() {
        PrintFile f = newFile(1L, 5);
        PrintOrder o = newOrder(1L, f.getId(), new BigDecimal("1.00"), OrderStatus.PAY_PENDING);

        BizException ex = catchThrowableOfType(
                () -> orderService.requireOwned(o.getOrderNo(), 999L), BizException.class);
        assertThat(ex).isNotNull();
        assertThat(ex.getCode()).isEqualTo(ResultCode.FORBIDDEN.code());
    }

    @Test
    @DisplayName("超时未支付订单被批量关闭")
    void closeExpiredOrders() {
        PrintFile f = newFile(1L, 5);
        PrintOrder expired = newOrder(1L, f.getId(), new BigDecimal("1.00"),
                OrderStatus.PAY_PENDING, LocalDateTime.now().minusHours(1));

        int closed = orderService.closeExpiredOrders();

        assertThat(closed).isGreaterThanOrEqualTo(1);
        assertThat(orderMapper.selectById(expired.getId()).getPayStatus())
                .isEqualTo(OrderStatus.PAY_CLOSED);
    }
}
