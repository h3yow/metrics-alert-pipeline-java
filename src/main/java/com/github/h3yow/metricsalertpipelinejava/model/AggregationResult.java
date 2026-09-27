package com.github.h3yow.metricsalertpipelinejava.model;

/**
 * 时间窗聚合结果，窗口为左闭右开 [windowStart, windowEnd)。
 * count 为 0 时 min/max/average 无意义（取 0）。
 */
public record AggregationResult(
        String metric,
        long windowStart,
        long windowEnd,
        long count,
        double sum,
        double min,
        double max,
        double average) {
}
