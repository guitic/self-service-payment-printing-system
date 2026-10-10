package com.print.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("t_print_order")
public class PrintOrder {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String orderNo;

    private Long userId;

    private Long fileId;

    private Long terminalId;

    /** 打印参数 JSON 快照 */
    private String paramsJson;

    private Integer pageCount;

    private BigDecimal amount;

    /** 0 待支付 1 已支付 2 已关闭 3 已退款 */
    private Integer payStatus;

    /** 0 待打印 1 打印中 2 已完成 3 失败 */
    private Integer printStatus;

    private Long priceRuleId;

    private LocalDateTime expireAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
