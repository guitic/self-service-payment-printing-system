package com.print.pricing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("t_price_rule")
public class PriceRule {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long merchantId;

    private String name;

    /** A4 / A3 */
    private String paperSize;

    /** 彩色单价 / 页 */
    private BigDecimal colorPrice;

    /** 黑白单价 / 页 */
    private BigDecimal monoPrice;

    /** 双面折扣，如 0.90 */
    private BigDecimal duplexDiscount;

    /** 起印价 */
    private BigDecimal minCharge;

    /** 1 启用 0 停用 */
    private Integer status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
