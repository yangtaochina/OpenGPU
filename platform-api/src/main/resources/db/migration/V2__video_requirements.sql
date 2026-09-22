-- =====================================================================
-- V2: 视频要求 + 节点显卡能力匹配
--
-- 设计要点：
--   用户在提交任务时选择视频要求（分辨率/时长/帧率），平台据此推导出
--   所需显存下限与显卡档位；Worker 注册时上报自身显卡能力；
--   任务领取时按能力过滤，只有满足要求的节点才能领到该任务。
--
--   已有历史任务的新增列全部为 NULL，语义为「不限定硬件要求」，
--   因此对存量数据完全向后兼容。
-- =====================================================================

ALTER TABLE task
    ADD COLUMN resolution          VARCHAR(16),
    ADD COLUMN duration_seconds    INTEGER,
    ADD COLUMN fps                 INTEGER,
    ADD COLUMN required_vram_mb    INTEGER,
    ADD COLUMN min_gpu_tier        SMALLINT,
    ADD COLUMN requirement_summary VARCHAR(255);

COMMENT ON COLUMN task.resolution          IS '分辨率档位：480P / 720P / 1080P / 4K';
COMMENT ON COLUMN task.required_vram_mb    IS '推导出的显存下限(MB)，领取时的硬约束';
COMMENT ON COLUMN task.min_gpu_tier        IS '所需显卡档位 rank：1=ENTRY 2=STANDARD 3=PRO 4=ULTRA';
COMMENT ON COLUMN task.requirement_summary IS '面向用户展示的要求摘要';

ALTER TABLE worker
    ADD COLUMN gpu_tier              SMALLINT,
    ADD COLUMN max_duration_seconds  INTEGER,
    ADD COLUMN supported_resolutions VARCHAR(128);

COMMENT ON COLUMN worker.gpu_tier             IS '节点显卡档位 rank：1=ENTRY 2=STANDARD 3=PRO 4=ULTRA';
COMMENT ON COLUMN worker.max_duration_seconds IS '该节点可承受的最长时长(秒)；NULL 或 <=0 表示不限制';

-- 领取查询的过滤条件：status + 显存下限 + 档位
CREATE INDEX idx_task_claim_capability
    ON task (status, required_vram_mb, min_gpu_tier, created_at);
