package com.print.payment.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("t_payment_record")
public class PaymentRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String orderNo;

    /** mock / wechat / alipay */
    private String gateway;

    private String tradeNo;

    private BigDecimal amount;

    /** 0 待支付 1 成功 2 失败 3 退款 */
    private Integer status;

    private LocalDateTime payTime;

    /** 原始回调报文（审计） */
    private String rawNotify;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
