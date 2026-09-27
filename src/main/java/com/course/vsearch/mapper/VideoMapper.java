package com.course.vsearch.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.course.vsearch.entity.Video;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface VideoMapper extends BaseMapper<Video> {

    @Select("SELECT * FROM video WHERE md5 = #{md5} ORDER BY id LIMIT 1")
    Video selectByMd5(@Param("md5") String md5);

    @Select("SELECT * FROM video WHERE video_id = #{videoId}")
    Video selectByVideoId(@Param("videoId") String videoId);

    /** 未完成任务（status: 0=PENDING / 1=PROCESSING）。启动自愈扫描用，不含 FAILED（需用户重传才会重试） */
    @Select("SELECT * FROM video WHERE status IN (0, 1) ORDER BY id")
    List<Video> selectUnfinished();

    /** 视频库列表：全部视频按上传时间倒序（id 自增即上传顺序） */
    @Select("SELECT * FROM video ORDER BY id DESC")
    List<Video> selectAllOrdered();

    @Delete("DELETE FROM video WHERE video_id = #{videoId}")
    int deleteByVideoId(@Param("videoId") String videoId);
}
