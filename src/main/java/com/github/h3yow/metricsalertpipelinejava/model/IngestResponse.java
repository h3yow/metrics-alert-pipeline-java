package com.github.h3yow.metricsalertpipelinejava.model;

import java.util.List;

/**
 * 一批样本的接入汇总。三类计数互斥且等于样本总数，
 * results 与输入顺序一一对应，便于调用方核对每条结论。
 */
public record IngestResponse(int accepted, int ignored, int rejected, List<SampleResult> results) {
}
