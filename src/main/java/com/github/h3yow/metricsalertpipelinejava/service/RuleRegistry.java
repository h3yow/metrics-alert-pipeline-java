package com.github.h3yow.metricsalertpipelinejava.service;

import com.github.h3yow.metricsalertpipelinejava.model.rule.AlertRule;

import java.util.List;

/** 规则登记处：注册、查询、列出。 */
public interface RuleRegistry {

    AlertRule register(AlertRule rule);

    AlertRule get(String ruleId);

    List<AlertRule> list();
}
