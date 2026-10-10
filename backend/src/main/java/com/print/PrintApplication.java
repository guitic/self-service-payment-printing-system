package com.print;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 自助付费打印系统 - 后端服务入口。
 *
 * <p>运行档位：
 * <ul>
 *   <li>{@code demo}（默认）：H2 内存库 + 内存队列 + 日志短信 + Mock 支付网关，零外部依赖直接跑通全链路</li>
 *   <li>{@code prod}：MySQL 8 + Redis 7，按设计文档部署</li>
 * </ul>
 */
@SpringBootApplication
@MapperScan({
        "com.print.auth.mapper",
        "com.print.file.mapper",
        "com.print.pricing.mapper",
        "com.print.order.mapper",
        "com.print.payment.mapper",
        "com.print.task.mapper"
})
@EnableScheduling
public class PrintApplication {

    public static void main(String[] args) {
        SpringApplication.run(PrintApplication.class, args);
    }
}
