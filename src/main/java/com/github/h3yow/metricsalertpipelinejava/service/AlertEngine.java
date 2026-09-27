package com.github.h3yow.metricsalertpipelinejava.service;

import com.github.h3yow.metricsalertpipelinejava.model.rule.RuleStatus;

import java.util.List;

/**
 * 告警引擎：基于滑动窗口对已登记规则做判定与状态流转，
 * 状态迁移确定、可追溯、重复触发被抑制。
 */
public interface AlertEngine {

    /** 对所有规则做一次判定。 */
    List<RuleStatus> evaluateAll();

    /** 对指定规则做一次判定；未知规则抛 ApiException。 */
    RuleStatus evaluate(String ruleId);

    /** 读取规则当前状态快照；未知规则抛 ApiException。 */
    RuleStatus status(String ruleId);
}
