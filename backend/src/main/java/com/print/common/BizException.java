package com.print.common;

import lombok.Getter;

/**
 * 业务异常，由 {@link GlobalExceptionHandler} 统一转换为 {@link Result}。
 */
@Getter
public class BizException extends RuntimeException {

    private final int code;

    public BizException(ResultCode rc) {
        super(rc.message());
        this.code = rc.code();
    }

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    public static BizException of(ResultCode rc) {
        return new BizException(rc);
    }
}
