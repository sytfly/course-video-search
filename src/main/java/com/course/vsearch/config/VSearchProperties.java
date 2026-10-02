package com.course.vsearch.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * 平台全部业务配置，前缀 vsearch。
 */
@Data
@ConfigurationProperties(prefix = "vsearch")
public class VSearchProperties {

    private SiliconFlow siliconflow = new SiliconFlow();
    private Redis redis = new Redis();
    private Minio minio = new Minio();
    private Ffmpeg ffmpeg = new Ffmpeg();
    private Upload upload = new Upload();
    private Segment segment = new Segment();
    private Pipeline pipeline = new Pipeline();
    private Rocketmq rocketmq = new Rocketmq();
    private Ratelimit ratelimit = new Ratelimit();
    private Retry retry = new Retry();
    private Search search = new Search();
    private Chapter chapter = new Chapter();
    private Progress progress = new Progress();
    private Eval eval = new Eval();

    @Data
    public static class SiliconFlow {
        private String baseUrl;
        private String apiKey;
        private String asrModel;
        private String embeddingModel;
        private String chatModel;
        private int embeddingDim = 1024;
        private int embeddingBatchSize = 16;
        /** 单次 API 调用响应读超时（秒）。实测单块 ASR 合法耗时最大 87.7s，取 120s 留余量 */
        private int responseTimeoutSeconds = 120;
    }

    @Data
    public static class Redis {
        private String host = "localhost";
        private int port = 6379;
        private String password;
        private int database = 0;
    }

    @Data
    public static class Minio {
        private String endpoint;
        private String accessKey;
        private String secretKey;
        private String bucket;
        private String publicEndpoint;
    }

    @Data
    public static class Ffmpeg {
        private String ffmpegPath;
        private String ffprobePath;
        private String workDir;
        private String audioBitrate;
        /**
         * 双轨合并前允许的两轨时长偏差比例（相对较长的轨），超过即拒绝合并。
         * 同一视频的画面轨与声音轨时长几乎相等；偏差过大说明两份文件本就不同源
         * （如画面取自 A、声音取自 B），合并只会产出「画面播 A、检索结果全是 B」的音画错位视频。
         */
        private double muxMaxDurationDrift = 0.1;
        private double vadNoiseDb = -35;
        private double vadMinSilence = 0.8;
        private int chunkMinSeconds = 8;
        private int chunkTargetSeconds = 20;
        private int chunkMaxSeconds = 30;
    }

    /**
     * 分片上传（大文件断点续传）。前端 File.slice 切片并行上传，服务端按序合并后走与单次上传
     * 完全相同的入库路径（内容指纹去重 + 上传锁 + 后台预处理），故两条路径的语义与进度口径一致。
     */
    @Data
    public static class Upload {
        /** 单个文件上限：与 spring.servlet.multipart.max-file-size 保持一致，避免超限文件先传完再被拒 */
        private DataSize maxFileSize = DataSize.ofGigabytes(2);
        /** 客户端未指定分片大小（或指定值越界）时的默认分片大小 */
        private DataSize chunkSize = DataSize.ofMegabytes(5);
        /** 上传会话（Redis 分片位图 + 已落盘分片）保留时长，超时后客户端只能重新上传 */
        private int sessionTtlHours = 24;
        /**
         * 一次批量上传最多可选多少个文件。多于此值直接拒绝：处理流水线只有 2 路并发，
         * 而每个视频的预处理 + ASR 是分钟级，批次越大最后一条记录的等待越久。
         */
        private int maxBatchFiles = 20;
    }

    @Data
    public static class Segment {
        /** fixed | semantic | acoustic */
        private String strategy = "semantic";
        private Fixed fixed = new Fixed();
        private Semantic semantic = new Semantic();
        private Acoustic acoustic = new Acoustic();
    }

    @Data
    public static class Fixed {
        private int windowSeconds = 60;
    }

    @Data
    public static class Semantic {
        private int minSegmentSeconds = 30;
        private int maxSegmentSeconds = 300;
        private double boundaryK = 0.8;
    }

    @Data
    public static class Acoustic {
        private int minSegmentSeconds = 30;
        private int maxSegmentSeconds = 300;
        private double pauseSeconds = 1.2;
    }

    @Data
    public static class Pipeline {
        /** local | rocketmq */
        private String type = "local";
        private int localThreads = 2;
        /** 单视频内 ASR 音频块的并发识别数（与视频任务级 localThreads 无关） */
        private int asrConcurrency = 5;
        /**
         * 启动自愈：启动时把「状态仍是 PENDING/PROCESSING、但处理锁空闲」的任务重新入队，
         * 复用 video_asr_chunk 断点续跑。进程被强杀后重启不再丢任务；多实例下靠处理锁保证只被处理一次。
         */
        private boolean startupRecovery = true;
    }

    @Data
    public static class Rocketmq {
        private String nameServer;
        private String group;
        private String consumerGroup;
        private String topic;
    }

    @Data
    public static class Ratelimit {
        private double permitsPerSecond = 2;
    }

    @Data
    public static class Retry {
        private int maxAttempts = 5;
        private long baseDelayMs = 1000;
        private long maxDelayMs = 30000;
    }

    @Data
    public static class Search {
        /** vector | hybrid */
        private String mode = "hybrid";
        /** 召回候选池大小：闸门与排序都在池内进行，最终只返回 topK */
        private int candidatePool = 50;
        /**
         * 高置信阈值：相关性达到该值才算「高置信」并按 topK 正常返回。
         * 低于它的候选不再被丢弃，而是返回前 {@link #lowConfidenceLimit} 条并全部打上 lowConfidence 标记（软闸门）。
         */
        private double minSimilarity = 0.6;
        /**
         * 低置信（best ∈ [低地板, 高置信阈值)）时最多交付的条数。
         * 历史上只给 1 条，但 20 条 QA 复测发现 5 条正确答案排在第 2/3 名（best 分低于 0.6），
         * 单条截断让用户拿得到第 1 名却拿不到真正的答案；放宽到 3 条弱展示，
         * 前端逐条标「低置信」+ 顶部整批提示，无关查询铺满一屏的风险仍被低地板挡住。
         */
        private int lowConfidenceLimit = 3;
        /**
         * 低地板：相关性低于该值的候选一律不返回（宁缺毋滥的兜底），返回空表示确实没有相关内容。
         * 介于 [低地板, 高置信阈值) 的候选最多返回 lowConfidenceLimit 条并标记 lowConfidence。
         * 为什么需要两个阈值：实测（16 条真实标注 + 6 条无关问句）正确片段的余弦跨 0.502~0.880，
         * 而无关问句的最高余弦也有 0.537，两簇重叠——单一硬阈值必然要么漏召回、要么放进无关内容。
         * 取 0.4 的依据：低于 0.4 的实测样本全是无关问句（签证 0.391 等），可让 0.502 起的正确片段全部存活。
         */
        private double minSimilarityFloor = 0.4;
        /**
         * 关键词命中加成上限：查询词被片段原文精确命中时，最多把相关性抬高该值。
         * 用于抵消纯语义召回对缩写/专有术语（RDB、AOF）的漏判；设为 0 即关闭关键词加成。
         */
        private double keywordBoost = 0.1;
    }

    @Data
    public static class Chapter {
        private boolean enabled = true;
    }

    @Data
    public static class Progress {
        private int ttlHours = 24;
        /**
         * 进度静默超过该秒数且处理锁空闲时，GET /api/video/{id} 才把未完成任务标为 interrupted。
         * 用于排除「刚入队、MQ 消息尚未被消费」的正常窗口（此时锁本来空闲）。
         */
        private int interruptedAfterSeconds = 60;
    }

    @Data
    public static class Eval {
        /** 开启后启动时读取 eval/*.json 跑分段/检索评估报告 */
        private boolean enabled = false;
    }
}
