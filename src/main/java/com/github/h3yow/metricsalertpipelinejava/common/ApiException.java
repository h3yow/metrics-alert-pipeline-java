package com.github.h3yow.metricsalertpipelinejava.common;

/** 业务可预期失败，携带稳定错误码，由全局处理器统一转成响应体。 */
public class ApiException extends RuntimeException {

    private final ErrorCode code;

    public ApiException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode getCode() {
        return code;
    }
}
