package me.asikeida.docweave.auth.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.Map;
import me.asikeida.docweave.auth.dto.CurrentUser;
import me.asikeida.docweave.auth.dto.LoginRequest;
import me.asikeida.docweave.auth.dto.TokenResponse;
import me.asikeida.docweave.auth.service.AuthService;
import me.asikeida.docweave.auth.service.LoginAttemptLimiter;
import me.asikeida.docweave.auth.web.AuthCookies;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "账号认证")
public class AuthController {
    private final AuthService auth;
    private final AuthCookies cookies;
    private final LoginAttemptLimiter limiter;

    public AuthController(AuthService auth, AuthCookies cookies, LoginAttemptLimiter limiter) {
        this.auth = auth;
        this.cookies = cookies;
        this.limiter = limiter;
    }

    @GetMapping("/csrf")
    @Operation(summary = "取得 CSRF 令牌", description = "浏览器先请求此接口，然后将 token 放入返回的 headerName 请求头。刷新凭据和 CSRF Cookie 均不可由 JavaScript 读取。")
    Map<String, String> csrf(@Parameter(hidden = true) CsrfToken token, HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        return Map.of("headerName", token.getHeaderName(), "token", token.getToken());
    }

    @PostMapping("/login")
    @Parameter(name = "X-XSRF-TOKEN", in = ParameterIn.HEADER, required = true, description = "csrf 接口返回的 token")
    @Operation(summary = "账号密码登录", description = "需 CSRF 请求头；返回短期 JWT，并写入 HttpOnly 刷新 Cookie。")
    TokenResponse login(@Valid @RequestBody LoginRequest request,
            @CookieValue(name = AuthCookies.REFRESH_COOKIE, required = false) String previous,
            HttpServletRequest servletRequest, HttpServletResponse response) {
        limiter.check(servletRequest.getRemoteAddr());
        return respond(auth.login(request.username(), request.password(), previous), response);
    }

    @PostMapping("/refresh")
    @Parameter(name = "X-XSRF-TOKEN", in = ParameterIn.HEADER, required = true, description = "csrf 接口返回的 token")
    @Operation(summary = "轮换刷新凭据并取得新 JWT", description = "需 CSRF 请求头；仅从 HttpOnly Cookie 读取刷新凭据，不接受请求体中的令牌。旧刷新凭据重放会撤销整次登录。")
    TokenResponse refresh(@CookieValue(name = AuthCookies.REFRESH_COOKIE, required = false) String refresh,
            HttpServletResponse response) {
        return respond(auth.refresh(refresh), response);
    }

    @GetMapping("/me")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "查询当前账号", description = "只返回账号身份，不代表已加入企业或获得任何知识库权限。")
    CurrentUser me(@AuthenticationPrincipal Jwt jwt, HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        return auth.currentUser(jwt);
    }

    @PostMapping("/logout")
    @Parameter(name = "X-XSRF-TOKEN", in = ParameterIn.HEADER, required = true, description = "csrf 接口返回的 token")
    @Operation(summary = "撤销当前登录", description = "需 CSRF 请求头；撤销登录后，尚未过期的旧 JWT 也不能继续访问。")
    Map<String, String> logout(@CookieValue(name = AuthCookies.REFRESH_COOKIE, required = false) String refresh,
            @AuthenticationPrincipal Jwt jwt, HttpServletResponse response) {
        auth.logout(refresh, jwt);
        response.addHeader(HttpHeaders.SET_COOKIE, cookies.clear());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        return Map.of("message", "已退出登录");
    }

    private TokenResponse respond(AuthService.IssuedLogin issued, HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookies.refresh(issued.refreshToken(), issued.refreshExpiresAt()));
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        return issued.response();
    }
}
