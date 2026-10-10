-- ============================================================
-- demo 档种子数据：计价规则 + 终端
--
-- 规则基线（与设计文档测试基线一致）：
--   A4 黑白 0.20/页、A4 彩色 0.80/页、A3 黑白 0.40/页
--   双面折扣 0.90、起印价 1.00
--
-- 注意：使用 MERGE + 显式主键，保证脚本**幂等**——测试套件中不同 profile
--       会各自启动 Spring 上下文，共享同一个 H2 内存库，重复执行不得冲突。
-- ============================================================

MERGE INTO t_price_rule (id, merchant_id, name, paper_size, color_price, mono_price,
                         duplex_discount, min_charge, status, created_at, updated_at, deleted)
KEY (id)
VALUES (1, 1, 'A4 标准', 'A4', 0.80, 0.20, 0.90, 1.00, 1, NOW(), NOW(), 0);

MERGE INTO t_price_rule (id, merchant_id, name, paper_size, color_price, mono_price,
                         duplex_discount, min_charge, status, created_at, updated_at, deleted)
KEY (id)
VALUES (2, 1, 'A3 标准', 'A3', 1.60, 0.40, 0.90, 1.00, 1, NOW(), NOW(), 0);

MERGE INTO t_terminal (id, merchant_id, code, name, agent_token, online_status,
                       printers_json, created_at, updated_at, deleted)
KEY (id)
VALUES (1, 1, 'TERM-001', '门店 1 号终端', 'dev-agent-token', 0, '[]', NOW(), NOW(), 0);
