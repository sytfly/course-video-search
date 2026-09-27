package com.course.vsearch.dto;

/** 注册请求：用户名 + 明文密码（只在 TLS 通道内传递，落库前立即 BCrypt 散列） */
public record RegisterRequest(String username, String password) {
}