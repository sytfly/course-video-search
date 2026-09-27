package com.course.vsearch.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
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

    /**
     * 归属校验：带上租户条件取视频，别人的 videoId 一律查不到（调用方转成 404）。
     * 显式写条件并跳过租户拦截器，是为了让「校验归属」这件事不依赖拦截器行为——
     * 票据鉴权的播放/进度流没有登录上下文，只能靠这里的显式条件。
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT * FROM video WHERE video_id = #{videoId} AND tenant_id = #{tenantId}")
    Video selectByVideoIdAndTenant(@Param("videoId") String videoId, @Param("tenantId") String tenantId);

    /** 未完成任务（status: 0=PENDING / 1=PROCESSING）。启动自愈扫描用，不含 FAILED（需用户重传才会重试） */
    @Select("SELECT * FROM video WHERE status IN (0, 1) ORDER BY id")
    List<Video> selectUnfinished();

    /** 视频库列表：全部视频按上传时间倒序（id 自增即上传顺序） */
    @Select("SELECT * FROM video ORDER BY id DESC")
    List<Video> selectAllOrdered();

    @Delete("DELETE FROM video WHERE video_id = #{videoId}")
    int deleteByVideoId(@Param("videoId") String videoId);
}
