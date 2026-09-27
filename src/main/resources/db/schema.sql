-- 课程视频语义检索平台 schema（幂等）
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

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

CREATE UNIQUE INDEX IF NOT EXISTS uk_video_md5 ON video (md5);

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

CREATE INDEX IF NOT EXISTS idx_segment_video ON video_segment (video_id, segment_index);

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

CREATE UNIQUE INDEX IF NOT EXISTS uk_asr_chunk ON video_asr_chunk (video_id, chunk_index);
