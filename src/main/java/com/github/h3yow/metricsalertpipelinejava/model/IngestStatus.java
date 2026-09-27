package com.github.h3yow.metricsalertpipelinejava.model;

/**
 * 单条样本接入后的判定结果。
 */
public enum IngestStatus {
    ACCEPTED,
    DUPLICATE,
    TOO_LATE,
    FUTURE_TIMESTAMP,
    INVALID_TIMESTAMP
}
