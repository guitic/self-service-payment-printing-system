package com.print.file.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_file")
public class PrintFile {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String originalName;

    /** 相对存储路径 */
    private String storagePath;

    private String contentType;

    private Long sizeBytes;

    /** 解析页数（计价依据） */
    private Integer pageCount;

    /** 0 已上传 1 已转换 */
    private Integer status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
