package com.course.vsearch.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.course.vsearch.entity.VideoSegment;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface VideoSegmentMapper extends BaseMapper<VideoSegment> {

    /** 向量余弦召回：score = 1 - cosine distance，越大越相关 */
    List<VideoSegment> vectorTopN(@Param("embedding") String embedding,
                                  @Param("videoId") String videoId,
                                  @Param("limit") int limit);

    /**
     * 关键词召回：查询词切出的 bigram 命中任意一个即入选。
     * score 同样返回语义余弦相似度（与向量召回同口径），
     * matchedGrams 返回命中的 bigram 个数（供相关性加成使用）。
     */
    List<VideoSegment> keywordTopN(@Param("grams") List<String> grams,
                                   @Param("embedding") String embedding,
                                   @Param("videoId") String videoId,
                                   @Param("limit") int limit);

    int deleteByVideoId(@Param("videoId") String videoId);

    int countByVideoId(@Param("videoId") String videoId);

    List<VideoSegment> listByVideoId(@Param("videoId") String videoId);

    /**
     * 全部视频的片段骨架（仅索引/起止时间/章节标题，不含正文与向量）。
     * 视频库列表一次取完，避免逐视频查询造成 N+1。
     */
    List<VideoSegment> listBriefAll();
}
