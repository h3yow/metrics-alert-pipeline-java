package com.github.h3yow.metricsalertpipelinejava.model;

/** 单条样本的接入判定结果：原样本 + 状态 + 可判定原因。 */
public record SampleResult(Sample sample, IngestStatus status, String reason) {
}
