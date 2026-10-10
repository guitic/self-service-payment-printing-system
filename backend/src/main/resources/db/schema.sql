-- ============================================================
-- 自助付费打印系统 - 表结构（H2 MySQL 兼容模式 / demo 档自动执行）
-- 生产库对应版本见 db/mysql/schema.sql
-- ============================================================

CREATE TABLE IF NOT EXISTS t_user (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    phone         VARCHAR(20)  NOT NULL,
    nickname      VARCHAR(64),
    status        TINYINT      DEFAULT 1,
    last_login_at DATETIME,
    created_at    DATETIME,
    updated_at    DATETIME,
    deleted       TINYINT      DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_user_phone ON t_user (phone);

CREATE TABLE IF NOT EXISTS t_file (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id       BIGINT       NOT NULL,
    original_name VARCHAR(255) NOT NULL,
    storage_path  VARCHAR(512) NOT NULL,
    content_type  VARCHAR(128),
    size_bytes    BIGINT       DEFAULT 0,
    page_count    INT          DEFAULT 0,
    status        TINYINT      DEFAULT 0,
    created_at    DATETIME,
    updated_at    DATETIME,
    deleted       TINYINT      DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_file_user ON t_file (user_id);

CREATE TABLE IF NOT EXISTS t_price_rule (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    merchant_id     BIGINT       DEFAULT 1,
    name            VARCHAR(64)  NOT NULL,
    paper_size      VARCHAR(8)   NOT NULL,
    color_price     DECIMAL(10, 2) NOT NULL,
    mono_price      DECIMAL(10, 2) NOT NULL,
    duplex_discount DECIMAL(5, 2)  DEFAULT 1.00,
    min_charge      DECIMAL(10, 2) DEFAULT 0.00,
    status          TINYINT      DEFAULT 1,
    created_at      DATETIME,
    updated_at      DATETIME,
    deleted         TINYINT      DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_rule_paper ON t_price_rule (paper_size, status);

CREATE TABLE IF NOT EXISTS t_print_order (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no      VARCHAR(32)    NOT NULL,
    user_id       BIGINT         NOT NULL,
    file_id       BIGINT         NOT NULL,
    terminal_id   BIGINT,
    params_json   TEXT,
    page_count    INT            DEFAULT 0,
    amount        DECIMAL(10, 2) NOT NULL,
    pay_status    TINYINT        DEFAULT 0,
    print_status  TINYINT        DEFAULT 0,
    price_rule_id BIGINT,
    expire_at     DATETIME,
    created_at    DATETIME,
    updated_at    DATETIME,
    deleted       TINYINT        DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_order_no ON t_print_order (order_no);
CREATE INDEX IF NOT EXISTS idx_order_user ON t_print_order (user_id);
CREATE INDEX IF NOT EXISTS idx_order_pay_status ON t_print_order (pay_status);

CREATE TABLE IF NOT EXISTS t_payment_record (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no   VARCHAR(32)    NOT NULL,
    gateway    VARCHAR(20)    NOT NULL,
    trade_no   VARCHAR(64),
    amount     DECIMAL(10, 2) NOT NULL,
    status     TINYINT        DEFAULT 0,
    pay_time   DATETIME,
    raw_notify TEXT,
    created_at DATETIME,
    updated_at DATETIME,
    deleted    TINYINT        DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_pay_gateway_trade ON t_payment_record (gateway, trade_no);
CREATE INDEX IF NOT EXISTS idx_pay_order ON t_payment_record (order_no);

CREATE TABLE IF NOT EXISTS t_terminal (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    merchant_id    BIGINT      DEFAULT 1,
    code           VARCHAR(32) NOT NULL,
    name           VARCHAR(64),
    agent_token    VARCHAR(64) NOT NULL,
    online_status  TINYINT     DEFAULT 0,
    last_heartbeat DATETIME,
    printers_json  TEXT,
    created_at     DATETIME,
    updated_at     DATETIME,
    deleted        TINYINT     DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_terminal_code ON t_terminal (code);

CREATE TABLE IF NOT EXISTS t_print_task (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no    VARCHAR(32) NOT NULL,
    terminal_id BIGINT,
    printer_id  BIGINT,
    file_path   VARCHAR(512),
    params_json TEXT,
    status      TINYINT     DEFAULT 0,
    retry_count INT         DEFAULT 0,
    fail_reason VARCHAR(255),
    claimed_at  DATETIME,
    finished_at DATETIME,
    created_at  DATETIME,
    updated_at  DATETIME,
    deleted     TINYINT     DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_task_order ON t_print_task (order_no);
CREATE INDEX IF NOT EXISTS idx_task_status ON t_print_task (status);
