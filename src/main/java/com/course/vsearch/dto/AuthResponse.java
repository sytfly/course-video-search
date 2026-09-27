package com.course.vsearch.dto;

/** 登录 / 注册的返回：令牌 + 账号信息。前端只需存 token，其余字段用于界面显示 */
public record AuthResponse(String token, String username, String tenantId, long expiresInSeconds) {
}