package com.print.order;

/**
 * 订单状态字典与文本映射（CONTRACT.md §2）。
 */
public final class OrderStatus {

    // pay_status
    public static final int PAY_PENDING = 0;
    public static final int PAY_PAID = 1;
    public static final int PAY_CLOSED = 2;
    public static final int PAY_REFUNDED = 3;

    // print_status
    public static final int PRINT_PENDING = 0;
    public static final int PRINT_RUNNING = 1;
    public static final int PRINT_DONE = 2;
    public static final int PRINT_FAILED = 3;

    private OrderStatus() {
    }

    public static String payText(Integer s) {
        if (s == null) {
            return "未知";
        }
        return switch (s) {
            case PAY_PENDING -> "待支付";
            case PAY_PAID -> "已支付";
            case PAY_CLOSED -> "已关闭";
            case PAY_REFUNDED -> "已退款";
            default -> "未知";
        };
    }

    public static String printText(Integer s) {
        if (s == null) {
            return "未知";
        }
        return switch (s) {
            case PRINT_PENDING -> "待打印";
            case PRINT_RUNNING -> "打印中";
            case PRINT_DONE -> "已完成";
            case PRINT_FAILED -> "失败";
            default -> "未知";
        };
    }
}
