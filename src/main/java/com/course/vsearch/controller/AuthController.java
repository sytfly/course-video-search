package com.course.vsearch.controller;

import com.course.vsearch.common.Result;
import com.course.vsearch.dto.AuthResponse;
import com.course.vsearch.dto.LoginRequest;
import com.course.vsearch.dto.RegisterRequest;
import com.course.vsearch.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 账号接口。register / login 在 SecurityConfig 里放行，其余接口都要带 Bearer 令牌。
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /** 注册：自动开一个租户，返回令牌，注册即可用 */
    @PostMapping("/register")
    public Result<AuthResponse> register(@RequestBody RegisterRequest request) {
        return Result.ok(authService.register(request));
    }

    @PostMapping("/login")
    public Result<AuthResponse> login(@RequestBody LoginRequest request) {
        return Result.ok(authService.login(request));
    }

    /** 当前登录账号（前端启动时校验本地令牌是否仍有效） */
    @GetMapping("/me")
    public Result<AuthResponse> me() {
        return Result.ok(authService.me());
    }
}