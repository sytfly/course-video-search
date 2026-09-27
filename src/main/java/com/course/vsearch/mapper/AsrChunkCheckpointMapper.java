package com.course.vsearch.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.course.vsearch.entity.AsrChunkCheckpoint;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.util.List;

public interface AsrChunkCheckpointMapper extends BaseMapper<AsrChunkCheckpoint> {

    @Select("SELECT * FROM video_asr_chunk WHERE video_id = #{videoId} ORDER BY chunk_index")
    List<AsrChunkCheckpoint> listByVideoId(@Param("videoId") String videoId);

    /**
     * 单条落库（幂等 upsert）。分块参数变化时同一下标可能对应新区间，直接覆盖旧值以避开
     * 唯一索引冲突。单语句提交、不包事务——崩溃时已完成块已落库，这正是断点的来源。
     */
    @Insert("INSERT INTO video_asr_chunk (video_id, chunk_index, start_time, end_time, text_content) "
            + "VALUES (#{videoId}, #{chunkIndex}, #{startTime}, #{endTime}, #{textContent}) "
            + "ON CONFLICT (video_id, chunk_index) DO UPDATE SET "
            + "start_time = EXCLUDED.start_time, "
            + "end_time = EXCLUDED.end_time, "
            + "text_content = EXCLUDED.text_content, "
            + "created_at = now()")
    int upsert(@Param("videoId") String videoId,
               @Param("chunkIndex") int chunkIndex,
               @Param("startTime") BigDecimal startTime,
               @Param("endTime") BigDecimal endTime,
               @Param("textContent") String textContent);

    /** 删除该视频的全部 ASR 断点（删除视频时一并清理，避免留下无主的续跑数据） */
    @Delete("DELETE FROM video_asr_chunk WHERE video_id = #{videoId}")
    int deleteByVideoId(@Param("videoId") String videoId);
}