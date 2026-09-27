package com.github.h3yow.metricsalertpipelinejava.model.rule;

/**
 * 滑动窗口阈值规则：
 * 对 [now-windowSeconds, now) 内的平均值做比较；至少 minSamples 个样本才参与判定；
 * 连续 requiredBreaches 次判定越界才触发告警，抑制短时抖动。
 */
public record AlertRule(
        String id,
        String metric,
        long windowSeconds,
        double threshold,
        ComparisonOp operator,
        int requiredBreaches,
        int minSamples) {
}
