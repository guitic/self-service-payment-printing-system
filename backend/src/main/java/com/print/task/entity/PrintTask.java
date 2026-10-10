package com.print.task.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_print_task")
public class PrintTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String orderNo;

    private Long terminalId;

    private Long printerId;

    private String filePath;

    private String paramsJson;

    /** 0 待领取 1 已领取 2 打印中 3 成功 4 失败 5 重试中 */
    private Integer status;

    private Integer retryCount;

    private String failReason;

    /** 领取时间（用于超时回收） */
    private LocalDateTime claimedAt;

    private LocalDateTime finishedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
