package com.course.vsearch.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 租户 = 数据归属边界。当前账号体系是「一个用户一个租户」，
 * 表结构保留 id/name 两列，便于将来把多个用户挂到同一租户下。
 */
@Data
@TableName("tenant")
public class Tenant {

    /** 主键由业务生成（t_xxx / t_default），不是自增 */
    @TableId(type = IdType.INPUT)
    private String id;

    private String name;
    private LocalDateTime createdAt;
}