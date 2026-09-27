package com.github.h3yow.metricsalertpipelinejava.model.rule;

/**
 * 一次状态迁移记录，保证可追溯：何时、从什么状态、到什么状态、依据是什么
 * （比较值、窗口范围、窗口内样本数）。
 */
public record TransitionEvent(
        long timestamp,
        AlertState from,
        AlertState to,
        String reason,
        double evaluatedValue,
        long windowStart,
        long windowEnd,
        long sampleCount) {
}
