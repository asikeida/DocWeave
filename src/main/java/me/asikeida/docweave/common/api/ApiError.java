package me.asikeida.docweave.common.api;

import java.time.Instant;
import java.util.List;

/**
 * 返回给 API 调用方的标准错误载荷。
 *
 * <p>错误详情不包含异常堆栈、SQL 或服务器内部路径。调用方可以提供 {@code requestId} 给维护者定位服务端日志。</p>
 */
public record ApiError(
        String code,
        String message,
        String requestId,
        Instant timestamp,
        List<ApiFieldViolation> violations
) {

    /**
     * 创建不包含字段错误的 API 错误。
     */
    public static ApiError of(String code, String message, String requestId) {
        return new ApiError(code, message, requestId, Instant.now(), List.of());
    }

    /**
     * 创建包含请求字段错误的 API 错误。
     */
    public static ApiError validation(String requestId, List<ApiFieldViolation> violations) {
        return new ApiError("VALIDATION_ERROR", "请求参数校验失败", requestId, Instant.now(), violations);
    }
}
