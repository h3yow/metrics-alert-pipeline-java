package com.github.h3yow.metricsalertpipelinejava.model.rule;

import java.util.List;

/**
 * 规则的当前告警状态快照：状态机当前状态、最近一次判定值、连续越界次数、
 * 完整迁移轨迹（按时间有序）。
 */
public record RuleStatus(
        String ruleId,
        AlertState state,
        Double currentValue,
        long evaluatedAt,
        int breachStreak,
        List<TransitionEvent> transitions) {
}
