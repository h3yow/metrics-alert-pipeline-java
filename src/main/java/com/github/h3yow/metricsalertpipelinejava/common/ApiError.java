package com.github.h3yow.metricsalertpipelinejava.common;

/** 统一失败响应体：只暴露错误码、可读原因与时间戳。 */
public record ApiError(ErrorCode error, String message, long timestamp) {
}
