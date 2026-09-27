package com.course.vsearch.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.course.vsearch.entity.Tenant;

/** 租户表操作。该表在 MybatisPlusConfig 的白名单里，不参与租户过滤 */
public interface TenantMapper extends BaseMapper<Tenant> {
}