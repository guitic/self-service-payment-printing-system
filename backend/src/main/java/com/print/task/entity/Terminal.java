package com.print.task.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_terminal")
public class Terminal {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long merchantId;

    private String code;

    private String name;

    /** 助手鉴权令牌（X-Agent-Token） */
    private String agentToken;

    /** 0 离线 1 在线 */
    private Integer onlineStatus;

    private LocalDateTime lastHeartbeat;

    /** 打印机列表 JSON */
    private String printersJson;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
