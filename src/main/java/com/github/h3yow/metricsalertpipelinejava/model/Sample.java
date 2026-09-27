package com.github.h3yow.metricsalertpipelinejava.model;

/**
 * 一条指标样本。
 *
 * @param metric    指标名
 * @param timestamp 事件时间（epoch millis），不是处理时间
 * @param value     指标值
 */
public record Sample(String metric, long timestamp, double value) {
}
