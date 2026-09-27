package com.github.h3yow.metricsalertpipelinejava.model.rule;

/**
 * 告警状态机：
 * INSUFFICIENT_DATA -> NORMAL/ALERTING（数据足够后进入判定）
 * NORMAL -> ALERTING（连续越界达标，触发并记录迁移）
 * ALERTING -> RECOVERED（出现一次不越界判定即恢复；持续越界被抑制，不重复触发）
 * RECOVERED -> NORMAL（恢复后保持正常）或 ALERTING（再次连续越界，允许重新触发）
 */
public enum AlertState {
    INSUFFICIENT_DATA,
    NORMAL,
    ALERTING,
    RECOVERED
}
