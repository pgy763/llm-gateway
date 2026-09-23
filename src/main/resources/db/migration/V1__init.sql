-- =====================================================================
-- LLM Gateway 初始化表结构
-- 说明：Flyway 会在应用启动时自动执行本文件（文件名必须以 V{数字}__ 开头）
-- =====================================================================

-- ---------------------------------------------------------------------
-- 表 1：虚拟密钥
-- 业务系统不直接持有大模型厂商的真实密钥，而是持有网关签发的虚拟密钥。
-- 这样网关才能做鉴权、配额校验和用量统计。
-- 注意：演示项目直接存明文，生产环境应当只存哈希值。
-- ---------------------------------------------------------------------
CREATE TABLE api_key
(
    id           BIGSERIAL PRIMARY KEY,
    key_value    VARCHAR(64) NOT NULL UNIQUE,
    display_name VARCHAR(64) NOT NULL,
    enabled      BOOLEAN     NOT NULL DEFAULT TRUE,
    quota_tokens BIGINT      NOT NULL DEFAULT 1000000,
    used_tokens  BIGINT      NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

-- ---------------------------------------------------------------------
-- 表 2：调用日志
-- 每一次对上游模型的调用都记一行，用于计费、排查问题和统计成功率。
-- ---------------------------------------------------------------------
CREATE TABLE usage_log
(
    id                BIGSERIAL PRIMARY KEY,
    request_id        VARCHAR(64) NOT NULL,
    api_key_id        BIGINT REFERENCES api_key (id),
    provider_name     VARCHAR(32),
    model             VARCHAR(64),
    prompt_tokens     INTEGER     NOT NULL DEFAULT 0,
    completion_tokens INTEGER     NOT NULL DEFAULT 0,
    total_tokens      INTEGER     NOT NULL DEFAULT 0,
    latency_ms        BIGINT      NOT NULL DEFAULT 0,
    success           BOOLEAN     NOT NULL DEFAULT TRUE,
    fallback_used     BOOLEAN     NOT NULL DEFAULT FALSE,
    error_message     TEXT,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_usage_log_created_at ON usage_log (created_at DESC);
CREATE INDEX idx_usage_log_api_key ON usage_log (api_key_id);

-- ---------------------------------------------------------------------
-- 表 3：AI 任务（多步编排的核心）
-- 关键设计：state 字段把"任务走到哪一步"持久化到数据库。
-- 服务重启后，从这张表读回 state，就能接着往下跑，不会丢任务。
-- version 字段用于乐观锁，防止两个线程同时改同一个任务。
-- ---------------------------------------------------------------------
CREATE TABLE ai_task
(
    id            BIGSERIAL PRIMARY KEY,
    task_id       VARCHAR(64) NOT NULL UNIQUE,
    api_key_id    BIGINT REFERENCES api_key (id),
    goal          TEXT        NOT NULL,
    state         VARCHAR(32) NOT NULL,
    retry_count   INTEGER     NOT NULL DEFAULT 0,
    max_retry     INTEGER     NOT NULL DEFAULT 2,
    draft_content TEXT,
    final_content TEXT,
    last_error    TEXT,
    last_review_comment TEXT,
    version       BIGINT      NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_ai_task_state ON ai_task (state);

-- ---------------------------------------------------------------------
-- 表 4：状态流转日志
-- 每次状态变化都追加一行。相当于任务的"行车记录仪"，
-- 出问题时能完整还原它是怎么一步步走到当前状态的。
-- ---------------------------------------------------------------------
CREATE TABLE task_state_log
(
    id         BIGSERIAL PRIMARY KEY,
    task_id    VARCHAR(64) NOT NULL,
    from_state VARCHAR(32),
    to_state   VARCHAR(32) NOT NULL,
    event      VARCHAR(32) NOT NULL,
    detail     TEXT,
    created_at TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_task_state_log_task ON task_state_log (task_id, id);

-- ---------------------------------------------------------------------
-- 种子数据：一个可直接使用的演示密钥
-- ---------------------------------------------------------------------
INSERT INTO api_key (key_value, display_name, quota_tokens)
VALUES ('sk-demo-0001', '演示密钥', 1000000);
