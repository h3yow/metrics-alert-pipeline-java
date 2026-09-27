package com.github.h3yow.metricsalertpipelinejava.exception;

/**
 * 对外可判定的失败原因，彼此可区分；不向调用方透出内部异常。
 */
public enum ApiErrorCode {
    UNKNOWN_METRIC,
    UNKNOWN_RULE,
    ILLEGAL_WINDOW,
    OUT_OF_RANGE,
    INVALID_RULE,
    EMPTY_BATCH,
    INVALID_PAYLOAD,
    INTERNAL_ERROR
}
