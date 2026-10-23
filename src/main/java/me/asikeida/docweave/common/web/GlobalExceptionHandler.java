package me.asikeida.docweave.common.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import me.asikeida.docweave.common.api.ApiError;
import me.asikeida.docweave.common.api.ApiFieldViolation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * 将框架和未知异常转换为不会泄漏内部实现的 HTTP 错误响应。
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request
    ) {
        List<ApiFieldViolation> violations = exception.getBindingResult().getFieldErrors().stream()
                .map(this::toViolation)
                .toList();
        return ResponseEntity.badRequest().body(ApiError.validation((String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE, 0), violations));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ApiError> handleConstraintViolation(
            ConstraintViolationException exception,
            HttpServletRequest request
    ) {
        List<ApiFieldViolation> violations = exception.getConstraintViolations().stream()
                .map(violation -> new ApiFieldViolation(violation.getPropertyPath().toString(), violation.getMessage()))
                .toList();
        return ResponseEntity.badRequest().body(ApiError.validation(requestId(request), violations));
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception exception,
            Object body,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request
    ) {
        String requestId = (String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE, 0);
        HttpStatus knownStatus = HttpStatus.resolve(status.value());
        String code = knownStatus == null ? "HTTP_ERROR" : knownStatus.name();
        String message = knownStatus == null ? "请求处理失败" : knownStatus.getReasonPhrase();
        if (exception instanceof HttpMessageNotReadableException) {
            code = "MALFORMED_REQUEST";
            message = "请求体格式不正确";
        }
        // 保留框架给出的状态码和响应头，例如 405 的 Allow 头。
        return super.handleExceptionInternal(exception, ApiError.of(code, message, requestId), headers, status, request);
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> handleUnexpectedException(Exception exception, HttpServletRequest request) {
        String requestId = requestId(request);
        log.error("Unhandled request failure, requestId={}", requestId, exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiError.of(
                "INTERNAL_ERROR",
                "服务暂时不可用，请稍后重试",
                requestId
        ));
    }

    private ApiFieldViolation toViolation(FieldError fieldError) {
        return new ApiFieldViolation(fieldError.getField(), fieldError.getDefaultMessage());
    }

    private String requestId(HttpServletRequest request) {
        return (String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
    }
}
