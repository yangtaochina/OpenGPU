-- =====================================================================
-- 智由 AI 视频任务平台 — 初始表结构 (V1)
-- 对应需求文档 5.1 核心数据对象
-- =====================================================================

-- ---------------------------------------------------------------------
-- 平台账号（用户 / 管理员）
-- ---------------------------------------------------------------------
CREATE TABLE app_user (
    id            UUID         PRIMARY KEY,
    username      VARCHAR(64)  NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role          VARCHAR(16)  NOT NULL,
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uk_app_user_username ON app_user (username);

-- ---------------------------------------------------------------------
-- GPU 节点（Worker）
-- ---------------------------------------------------------------------
CREATE TABLE worker (
    id                UUID         PRIMARY KEY,
    name              VARCHAR(64)  NOT NULL,
    token_hash        VARCHAR(128) NOT NULL,
    status            VARCHAR(16)  NOT NULL,
    runtime_status    VARCHAR(16)  NOT NULL DEFAULT 'IDLE',
    current_task_id   UUID,
    gpu_model         VARCHAR(128),
    vram_mb           INTEGER,
    worker_version    VARCHAR(64),
    model_version     VARCHAR(128),
    last_heartbeat_at TIMESTAMPTZ,
    enabled           BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uk_worker_name ON worker (name);
CREATE INDEX idx_worker_status ON worker (status);
CREATE INDEX idx_worker_heartbeat ON worker (last_heartbeat_at);

-- ---------------------------------------------------------------------
-- 任务
-- ---------------------------------------------------------------------
CREATE TABLE task (
    id               UUID        PRIMARY KEY,
    prompt           TEXT        NOT NULL,
    status           VARCHAR(16) NOT NULL,
    progress         INTEGER     NOT NULL DEFAULT 0,
    retry_count      INTEGER     NOT NULL DEFAULT 0,
    max_retry        INTEGER     NOT NULL DEFAULT 2,
    worker_id        UUID,
    lease_id         UUID,
    lease_expires_at TIMESTAMPTZ,
    error_code       VARCHAR(64),
    error_message    TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at       TIMESTAMPTZ,
    finished_at      TIMESTAMPTZ,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 原子领取 (FOR UPDATE SKIP LOCKED) 与列表筛选的主要索引
CREATE INDEX idx_task_status_created ON task (status, created_at);
CREATE INDEX idx_task_worker ON task (worker_id);
-- 租约回收扫描
CREATE INDEX idx_task_lease_expires ON task (lease_expires_at);

-- ---------------------------------------------------------------------
-- 任务执行尝试（历史保留，不覆盖）
-- ---------------------------------------------------------------------
CREATE TABLE task_attempt (
    id            UUID        PRIMARY KEY,
    task_id       UUID        NOT NULL REFERENCES task (id) ON DELETE CASCADE,
    worker_id     UUID,
    lease_id      UUID,
    attempt_no    INTEGER     NOT NULL,
    status        VARCHAR(16) NOT NULL,
    error_code    VARCHAR(64),
    error_message TEXT,
    started_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    ended_at      TIMESTAMPTZ
);

CREATE INDEX idx_attempt_task ON task_attempt (task_id, attempt_no);
CREATE UNIQUE INDEX uk_attempt_lease ON task_attempt (lease_id);

-- ---------------------------------------------------------------------
-- 结果（task_id 唯一 => complete 天然幂等）
-- ---------------------------------------------------------------------
CREATE TABLE task_result (
    id               UUID        PRIMARY KEY,
    task_id          UUID        NOT NULL REFERENCES task (id) ON DELETE CASCADE,
    file_key         VARCHAR(512) NOT NULL,
    file_url         TEXT        NOT NULL,
    file_size        BIGINT      NOT NULL,
    checksum         VARCHAR(128),
    content_type     VARCHAR(128),
    duration_seconds DOUBLE PRECISION,
    width            INTEGER,
    height           INTEGER,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uk_result_task ON task_result (task_id);
