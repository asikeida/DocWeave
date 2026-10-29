package me.asikeida.docweave.auth.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import me.asikeida.docweave.auth.service.AuthFailure;
import me.asikeida.docweave.common.api.ApiError;
import me.asikeida.docweave.common.web.RequestIdFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AuthExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(AuthExceptionHandler.class);
    private final AuthCookies cookies;

    public AuthExceptionHandler(AuthCookies cookies) { this.cookies = cookies; }

    @ExceptionHandler(AuthFailure.class)
    ResponseEntity<ApiError> authenticationFailure(AuthFailure failure, HttpServletRequest request,
            HttpServletResponse response) {
        if (request.getRequestURI().equals("/api/v1/auth/refresh")) {
            response.addHeader(HttpHeaders.SET_COOKIE, cookies.clear());
        }
        log.warn("Authentication request rejected: code={}", failure.getCode());
        if (failure.getStatus() == 401) { response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer"); }
        if (failure.getStatus() == 429) { response.setHeader(HttpHeaders.RETRY_AFTER, "60"); }
        return ResponseEntity.status(failure.getStatus()).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiError.of(failure.getCode(), failure.getMessage(),
                        (String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE)));
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiError> accessDenied(HttpServletRequest request) {
        log.warn("Controller access denied");
        return ResponseEntity.status(403).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiError.of("ACCESS_DENIED", "请求未获授权",
                        (String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE)));
    }
}
