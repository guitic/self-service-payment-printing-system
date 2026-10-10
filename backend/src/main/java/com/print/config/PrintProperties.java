package com.print.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 业务配置（与设计文档第 2 章 §1.4 环境变量清单对齐）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "print")
public class PrintProperties {

    private FileCfg file = new FileCfg();
    private OrderCfg order = new OrderCfg();
    private TaskCfg task = new TaskCfg();
    private AgentCfg agent = new AgentCfg();
    private SmsCfg sms = new SmsCfg();
    private JwtCfg jwt = new JwtCfg();
    private QueueCfg queue = new QueueCfg();
    private PaymentCfg payment = new PaymentCfg();
    private AdminCfg admin = new AdminCfg();

    @Data
    public static class FileCfg {
        /** 本地存储根目录 */
        private String root = "./data/uploads";
        private long maxSizeMb = 50;
        /** 允许的扩展名白名单 */
        private List<String> allowedExt = List.of("doc", "docx", "xls", "xlsx", "pdf", "png", "jpg", "jpeg", "bmp", "gif");
        /** 转换器：none（不启用，Office 走体积估算）| libreoffice */
        private String converter = "none";
        /** LibreOffice 可执行文件路径 */
        private String libreofficePath = "soffice";
        /** 转换超时（秒） */
        private int convertTimeoutSeconds = 60;
    }

    @Data
    public static class OrderCfg {
        /** 未支付订单超时关闭（分钟） */
        private int payExpireMinutes = 15;
    }

    @Data
    public static class TaskCfg {
        /** 打印中任务超时回收阈值（秒） */
        private int claimTimeoutSeconds = 120;
        /** 任务最大重试次数 */
        private int maxRetry = 3;
        /** 助手长轮询 hold 秒数 */
        private int longPollSeconds = 25;
    }

    @Data
    public static class AgentCfg {
        /** 超过该秒数未心跳判定离线 */
        private int offlineSeconds = 90;
    }

    @Data
    public static class SmsCfg {
        private int codeTtlSeconds = 300;
        private int sendCooldownSeconds = 60;
        /** demo 档为 true：控制台打印验证码并在响应中返回 debugCode */
        private boolean debug = true;
    }

    @Data
    public static class JwtCfg {
        private String secret = "dev-only-secret-please-replace-with-32-bytes-min-key-0001";
        private int expireMinutes = 120;
    }

    @Data
    public static class QueueCfg {
        /** memory / redis */
        private String type = "memory";
    }

    @Data
    public static class PaymentCfg {
        /** mock / wechat / alipay */
        private String gateway = "mock";
        /**
         * 是否注册 Mock 支付端点（{@code /payments/mock/confirm}）。
         * <p><b>安全红线</b>：仅 demo / staging 置 true；生产必须为 false，
         * 否则任何登录用户可把任意订单标记为已支付并免费出纸。
         */
        private boolean mockEnabled = false;
        private WechatCfg wechat = new WechatCfg();
        private AlipayCfg alipay = new AlipayCfg();
    }

    /** 管理端权限配置 */
    @Data
    public static class AdminCfg {
        /** 管理员手机号白名单 */
        private List<String> phones = new ArrayList<>();
        /**
         * 是否把所有登录用户视为管理员。
         * <p><b>安全红线</b>：仅供 demo 档演示管理端使用，生产必须为 false。
         */
        private boolean allowAll = false;
    }

    /** 微信支付 V3 配置（商户密钥不入 Git，从环境变量注入） */
    @Data
    public static class WechatCfg {
        private boolean enabled = false;
        private String appId = "";
        private String mchId = "";
        private String apiV3Key = "";
        /** 商户 API 私钥文件路径（apiclient_key.pem） */
        private String privateKeyPath = "";
        private String certSerialNo = "";
        /** 微信支付平台证书路径（用于回调验签） */
        private String platformCertPath = "";
        private String notifyUrl = "";
        private String apiBase = "https://api.mch.weixin.qq.com";
    }

    /** 支付宝配置 */
    @Data
    public static class AlipayCfg {
        private boolean enabled = false;
        private String appId = "";
        /** 应用私钥（PKCS8，Base64） */
        private String privateKey = "";
        /** 支付宝公钥（验签用） */
        private String alipayPublicKey = "";
        private String notifyUrl = "";
        private String gateway = "https://openapi.alipay.com/gateway.do";
        private boolean sandbox = false;
    }
}
