package me.asikeida.docweave.common.api;

/**
 * 描述单个请求字段未满足的校验约束。
 */
public record ApiFieldViolation(String field, String message) {
}
