package com.github.h3yow.metricsalertpipelinejava.api;

import com.github.h3yow.metricsalertpipelinejava.exception.ApiErrorCode;
import com.github.h3yow.metricsalertpipelinejava.exception.ApiException;
import com.github.h3yow.metricsalertpipelinejava.model.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 统一异常出口：只把错误码与可读信息返回给调用方，绝不透出内部异常堆栈。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApi(ApiException ex) {
        HttpStatus status = ex.getCode() == ApiErrorCode.OUT_OF_RANGE
                ? HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE
                : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status)
                .body(new ErrorResponse(ex.getCode().name(), ex.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse(ApiErrorCode.INVALID_PAYLOAD.name(),
                        "request body is missing or malformed"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleOther(Exception ex) {
        // 兜底：内部异常不外泄，仅记录在服务端日志。
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse(ApiErrorCode.INTERNAL_ERROR.name(), "internal error"));
    }
}
