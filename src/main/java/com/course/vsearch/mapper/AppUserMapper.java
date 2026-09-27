package com.course.vsearch.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.course.vsearch.entity.AppUser;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 账号表操作。该表在 MybatisPlusConfig 的白名单里，登录必须能跨租户查到用户 */
public interface AppUserMapper extends BaseMapper<AppUser> {

    @Select("SELECT * FROM app_user WHERE username = #{username}")
    AppUser selectByUsername(@Param("username") String username);
}