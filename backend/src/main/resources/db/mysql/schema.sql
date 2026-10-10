-- ============================================================
-- 自助付费打印系统 - 生产库表结构（MySQL 8）
-- 初始化：mysql -u print -p print_db < db/mysql/schema.sql
-- ============================================================

CREATE TABLE IF NOT EXISTS t_user (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    phone         VARCHAR(20)  NOT NULL,
    nickname      VARCHAR(64)  DEFAULT NULL,
    status        TINYINT      DEFAULT 1,
    last_login_at DATETIME     DEFAULT NULL,
    created_at    DATETIME     DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted       TINYINT      DEFAULT 0,
    UNIQUE KEY uk_user_phone (phone)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '用户';

CREATE TABLE IF NOT EXISTS t_file (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id       BIGINT       NOT NULL,
    original_name VARCHAR(255) NOT NULL,
    storage_path  VARCHAR(512) NOT NULL,
    content_type  VARCHAR(128) DEFAULT NULL,
    size_bytes    BIGINT       DEFAULT 0,
    page_count    INT          DEFAULT 0,
    status        TINYINT      DEFAULT 0,
    created_at    DATETIME     DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted       TINYINT      DEFAULT 0,
    KEY idx_file_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '上传文件';

CREATE TABLE IF NOT EXISTS t_price_rule (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    merchant_id     BIGINT         DEFAULT 1,
    name            VARCHAR(64)    NOT NULL,
    paper_size      VARCHAR(8)     NOT NULL,
    color_price     DECIMAL(10, 2) NOT NULL,
    mono_price      DECIMAL(10, 2) NOT NULL,
    duplex_discount DECIMAL(5, 2)  DEFAULT 1.00,
    min_charge      DECIMAL(10, 2) DEFAULT 0.00,
    status          TINYINT        DEFAULT 1,
    created_at      DATETIME       DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME       DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted         TINYINT        DEFAULT 0,
    KEY idx_rule_paper (paper_size, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '计价规则';

CREATE TABLE IF NOT EXISTS t_print_order (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no      VARCHAR(32)    NOT NULL,
    user_id       BIGINT         NOT NULL,
    file_id       BIGINT         NOT NULL,
    terminal_id   BIGINT         DEFAULT NULL,
    params_json   TEXT,
    page_count    INT            DEFAULT 0,
    amount        DECIMAL(10, 2) NOT NULL,
    pay_status    TINYINT        DEFAULT 0 COMMENT '0待支付 1已支付 2已关闭 3已退款',
    print_status  TINYINT        DEFAULT 0 COMMENT '0待打印 1打印中 2已完成 3失败',
    price_rule_id BIGINT         DEFAULT NULL,
    expire_at     DATETIME       DEFAULT NULL,
    created_at    DATETIME       DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME       DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted       TINYINT        DEFAULT 0,
    UNIQUE KEY uk_order_no (order_no),
    KEY idx_order_user (user_id),
    KEY idx_order_pay_status (pay_status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '打印订单';

CREATE TABLE IF NOT EXISTS t_payment_record (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no   VARCHAR(32)    NOT NULL,
    gateway    VARCHAR(20)    NOT NULL,
    trade_no   VARCHAR(64)    DEFAULT NULL,
    amount     DECIMAL(10, 2) NOT NULL,
    status     TINYINT        DEFAULT 0 COMMENT '0待支付 1成功 2失败 3退款',
    pay_time   DATETIME       DEFAULT NULL,
    raw_notify TEXT,
    created_at DATETIME       DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME       DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted    TINYINT        DEFAULT 0,
    UNIQUE KEY uk_pay_gateway_trade (gateway, trade_no),
    KEY idx_pay_order (order_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '支付流水';

CREATE TABLE IF NOT EXISTS t_terminal (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    merchant_id    BIGINT      DEFAULT 1,
    code           VARCHAR(32) NOT NULL,
    name           VARCHAR(64) DEFAULT NULL,
    agent_token    VARCHAR(64) NOT NULL,
    online_status  TINYINT     DEFAULT 0,
    last_heartbeat DATETIME    DEFAULT NULL,
    printers_json  TEXT,
    created_at     DATETIME    DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME    DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted        TINYINT     DEFAULT 0,
    UNIQUE KEY uk_terminal_code (code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '终端';

CREATE TABLE IF NOT EXISTS t_print_task (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no    VARCHAR(32) NOT NULL,
    terminal_id BIGINT      DEFAULT NULL,
    printer_id  BIGINT      DEFAULT NULL,
    file_path   VARCHAR(512) DEFAULT NULL,
    params_json TEXT,
    status      TINYINT     DEFAULT 0 COMMENT '0待领取 1已领取 2打印中 3成功 4失败 5重试中',
    retry_count INT         DEFAULT 0,
    fail_reason VARCHAR(255) DEFAULT NULL,
    claimed_at  DATETIME    DEFAULT NULL,
    finished_at DATETIME    DEFAULT NULL,
    created_at  DATETIME    DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME    DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted     TINYINT     DEFAULT 0,
    UNIQUE KEY uk_task_order (order_no),
    KEY idx_task_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '打印任务';

-- 生产种子：计价规则 + 终端（按需执行）
INSERT INTO t_price_rule (merchant_id, name, paper_size, color_price, mono_price, duplex_discount, min_charge, status)
SELECT 1, 'A4 标准', 'A4', 0.80, 0.20, 0.90, 1.00, 1
WHERE NOT EXISTS (SELECT 1 FROM t_price_rule WHERE paper_size = 'A4');

INSERT INTO t_price_rule (merchant_id, name, paper_size, color_price, mono_price, duplex_discount, min_charge, status)
SELECT 1, 'A3 标准', 'A3', 1.60, 0.40, 0.90, 1.00, 1
WHERE NOT EXISTS (SELECT 1 FROM t_price_rule WHERE paper_size = 'A3');

INSERT INTO t_terminal (merchant_id, code, name, agent_token, online_status)
SELECT 1, 'TERM-001', '门店 1 号终端', 'dev-agent-token', 0
WHERE NOT EXISTS (SELECT 1 FROM t_terminal WHERE code = 'TERM-001');
