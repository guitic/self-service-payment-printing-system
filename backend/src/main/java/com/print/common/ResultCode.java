package com.print.common;

/**
 * 业务错误码（与 CONTRACT.md §6 一致）。
 */
public enum ResultCode {

    SUCCESS(0, "ok"),

    PARAM_ERROR(400, "参数校验失败"),
    UNAUTHORIZED(401, "未登录或 Token 无效"),
    FORBIDDEN(403, "无权限访问该资源"),
    NOT_FOUND(404, "资源不存在"),
    TOO_MANY_REQUESTS(429, "请求过于频繁"),

    SMS_CODE_ERROR(1001, "验证码错误"),
    SMS_CODE_EXPIRED(1002, "验证码已过期"),
    SMS_SEND_TOO_FREQUENT(1003, "验证码发送过于频繁"),

    FILE_TYPE_NOT_ALLOWED(2001, "不支持的文件类型"),
    FILE_TOO_LARGE(2002, "文件超过大小限制"),
    FILE_PARSE_FAILED(2003, "文件解析失败"),

    NO_PRICING_RULE(3001, "暂无可用计价规则"),
    PAGE_RANGE_INVALID(3002, "页码范围超出文件页数"),

    ORDER_NOT_FOUND(4001, "订单不存在"),
    ORDER_ALREADY_PAID(4002, "订单已支付不可取消"),
    ORDER_CLOSED(4003, "订单已关闭"),

    PAYMENT_GATEWAY_ERROR(5001, "支付网关错误");

    private final int code;
    private final String message;

    ResultCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int code() {
        return code;
    }

    public String message() {
        return message;
    }
}
