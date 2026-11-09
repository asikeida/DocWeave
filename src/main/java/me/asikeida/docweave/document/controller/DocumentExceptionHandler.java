package me.asikeida.docweave.document.controller;

import jakarta.servlet.http.HttpServletRequest;
import me.asikeida.docweave.common.api.ApiError;
import me.asikeida.docweave.common.web.RequestIdFilter;
import me.asikeida.docweave.document.domain.DocumentFailure;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 文档错误复用统一 ApiError 契约，不泄漏底层存储异常。 */
@RestControllerAdvice
@Order(-10)
public class DocumentExceptionHandler {
    @ExceptionHandler(DocumentFailure.class)
    ResponseEntity<ApiError> handle(DocumentFailure failure,HttpServletRequest request){
        String requestId=(String)request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        org.slf4j.LoggerFactory.getLogger(getClass()).warn("Document request rejected: code={}, requestId={}",failure.getCode(),requestId);
        return ResponseEntity.status(failure.getStatus()).body(ApiError.of(failure.getCode(),failure.getMessage(),requestId));
    }
}
