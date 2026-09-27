-- 课程视频语义检索平台 schema（幂等）
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- ---------------------------------------------------------------------------
-- 租户与账号
-- 一个用户 = 一个租户：tenant_id 就是数据归属边界，三张业务表都带这一列，
-- 检索/列表/详情/播放/删除一律按它过滤，保证账号之间互不可见。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS tenant (
    id         VARCHAR(64)  PRIMARY KEY,
    name       VARCHAR(128) NOT NULL,
    created_at TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS app_user (
    id            BIGSERIAL    PRIMARY KEY,
    username      VARCHAR(64)  NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    tenant_id     VARCHAR(64)  NOT NULL,
    created_at    TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_app_user_tenant ON app_user (tenant_id);

-- 默认租户：存量视频与默认管理员账号都归属它（INSERT ... ON CONFLICT 保证可重复启动）
INSERT INTO tenant (id, name) VALUES ('t_default', '默认租户') ON CONFLICT (id) DO NOTHING;

-- 视频主表
CREATE TABLE IF NOT EXISTS video (
    id           BIGSERIAL    PRIMARY KEY,
    video_id     VARCHAR(64)  NOT NULL UNIQUE,
    file_name    VARCHAR(255) NOT NULL,
    minio_url    VARCHAR(512) NOT NULL,
    md5          VARCHAR(64)  NOT NULL,
    duration     DECIMAL(10, 2),
    status       SMALLINT     NOT NULL DEFAULT 0,  -- 0待处理 1处理中 2完成 3失败
    error_msg    TEXT,
    created_at   TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at   TIMESTAMP    NOT NULL DEFAULT now()
);
COMMENT ON COLUMN video.status IS '0待处理 1处理中 2完成 3失败';

-- 租户列：先加可空列 → 回填存量行 → 再置 NOT NULL。
-- 刻意不用 DEFAULT：开发期若漏写 tenant_id，应该直接报非空约束错，而不是静默落进默认租户。
ALTER TABLE video ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64);
UPDATE video SET tenant_id = 't_default' WHERE tenant_id IS NULL;
ALTER TABLE video ALTER COLUMN tenant_id SET NOT NULL;

-- 去重键必须按租户隔离：旧的全局唯一索引会让「B 上传 A 传过的同一文件」直接命中 A 的记录，
-- 等于把 A 的 videoId 交给 B（串台），故拆成 (tenant_id, md5)。
DROP INDEX IF EXISTS uk_video_md5;
CREATE UNIQUE INDEX IF NOT EXISTS uk_video_tenant_md5 ON video (tenant_id, md5);

CREATE INDEX IF NOT EXISTS idx_video_tenant ON video (tenant_id, id DESC);

-- 视频片段表
CREATE TABLE IF NOT EXISTS video_segment (
    id             BIGSERIAL    PRIMARY KEY,
    video_id       VARCHAR(64)  NOT NULL,
    segment_index  INT          NOT NULL,
    start_time     DECIMAL(10, 2) NOT NULL,
    end_time       DECIMAL(10, 2) NOT NULL,
    text_content   TEXT         NOT NULL,
    corrected_text TEXT,
    embedding      vector(1024),
    chapter_title  VARCHAR(255),
    strategy       VARCHAR(16),
    created_at     TIMESTAMP    NOT NULL DEFAULT now()
);

ALTER TABLE video_segment ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64);
UPDATE video_segment SET tenant_id = 't_default' WHERE tenant_id IS NULL;
ALTER TABLE video_segment ALTER COLUMN tenant_id SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_segment_video ON video_segment (video_id, segment_index);

-- 检索按租户过滤（向量召回与关键词召回都带 tenant_id 条件）
CREATE INDEX IF NOT EXISTS idx_segment_tenant ON video_segment (tenant_id, video_id, segment_index);

-- ivfflat 余弦索引：数据量小 lists=100 足够；数据量增大后可调 lists 或换 HNSW/Milvus
-- 注意：ivfflat 建议在导入数据后（或表有一定数据时）建表效果更佳，此处先建后导亦可，
-- probes 由检索会话设置（默认 1），数据量大时可 SET LOCAL ivfflat.probes = 10。
-- 直接使用 PG 原生 CREATE INDEX IF NOT EXISTS（Spring 脚本拆分器不识别 DO $$ 美元引号）
CREATE INDEX IF NOT EXISTS idx_segment_embedding
    ON video_segment USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);

-- 混合检索：trigram 支持 ILIKE 关键词召回
CREATE INDEX IF NOT EXISTS idx_segment_text_trgm
    ON video_segment USING gin (text_content gin_trgm_ops);

-- ASR 块级断点：每块识别成功即落库，失败重传时复用，避免从头重跑
CREATE TABLE IF NOT EXISTS video_asr_chunk (
    id           BIGSERIAL      PRIMARY KEY,
    video_id     VARCHAR(64)    NOT NULL,
    chunk_index  INT            NOT NULL,
    start_time   DECIMAL(10, 2) NOT NULL,
    end_time     DECIMAL(10, 2) NOT NULL,
    text_content TEXT,
    created_at   TIMESTAMP      NOT NULL DEFAULT now()
);

ALTER TABLE video_asr_chunk ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64);
UPDATE video_asr_chunk SET tenant_id = 't_default' WHERE tenant_id IS NULL;
ALTER TABLE video_asr_chunk ALTER COLUMN tenant_id SET NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uk_asr_chunk ON video_asr_chunk (video_id, chunk_index);
CREATE INDEX IF NOT EXISTS idx_asr_chunk_tenant ON video_asr_chunk (tenant_id, video_id);