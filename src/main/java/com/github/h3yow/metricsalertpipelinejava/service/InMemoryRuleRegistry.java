package com.github.h3yow.metricsalertpipelinejava.service;

import com.github.h3yow.metricsalertpipelinejava.common.ApiException;
import com.github.h3yow.metricsalertpipelinejava.common.ErrorCode;
import com.github.h3yow.metricsalertpipelinejava.model.rule.AlertRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** 内存规则登记处：字段校验、ID 唯一、并发注册安全。 */
@Component
public class InMemoryRuleRegistry implements RuleRegistry {

    private static final Logger log = LoggerFactory.getLogger(InMemoryRuleRegistry.class);

    /** 允许的最大窗口（秒），与存储层窗口上限对齐。 */
    public static final long MAX_WINDOW_SECONDS = 7L * 24 * 60 * 60;

    private final ConcurrentMap<String, AlertRule> rules = new ConcurrentHashMap<>();

    @Override
    public AlertRule register(AlertRule rule) {
        validate(rule);
        AlertRule previous = rules.putIfAbsent(rule.id(), rule);
        if (previous != null) {
            throw new ApiException(ErrorCode.INVALID_RULE,
                    "rule id already exists: " + rule.id());
        }
        log.info("rule registered id={} metric={} window={}s threshold={} {} breaches={} minSamples={}",
                rule.id(), rule.metric(), rule.windowSeconds(), rule.threshold(),
                rule.operator(), rule.requiredBreaches(), rule.minSamples());
        return rule;
    }

    @Override
    public AlertRule get(String ruleId) {
        if (ruleId == null) {
            throw new ApiException(ErrorCode.RULE_NOT_FOUND, "rule id is null");
        }
        AlertRule rule = rules.get(ruleId);
        if (rule == null) {
            throw new ApiException(ErrorCode.RULE_NOT_FOUND, "rule not found: " + ruleId);
        }
        return rule;
    }

    @Override
    public List<AlertRule> list() {
        // 按 id 排序保证输出确定性，便于测试与排查。
        return rules.values().stream()
                .sorted(java.util.Comparator.comparing(AlertRule::id))
                .toList();
    }

    private void validate(AlertRule rule) {
        if (rule == null) {
            throw new ApiException(ErrorCode.INVALID_RULE, "rule must not be null");
        }
        if (rule.id() == null || rule.id().isBlank()) {
            throw new ApiException(ErrorCode.INVALID_RULE, "rule id must not be blank");
        }
        if (rule.metric() == null || rule.metric().isBlank()) {
            throw new ApiException(ErrorCode.INVALID_RULE, "metric must not be blank");
        }
        if (rule.windowSeconds() <= 0L || rule.windowSeconds() > MAX_WINDOW_SECONDS) {
            throw new ApiException(ErrorCode.INVALID_WINDOW,
                    "windowSeconds must be in (0, " + MAX_WINDOW_SECONDS + "], got "
                            + rule.windowSeconds());
        }
        if (rule.operator() == null) {
            throw new ApiException(ErrorCode.INVALID_RULE, "operator must not be null");
        }
        if (rule.requiredBreaches() <= 0) {
            throw new ApiException(ErrorCode.INVALID_RULE,
                    "requiredBreaches must be positive, got " + rule.requiredBreaches());
        }
        if (rule.minSamples() <= 0) {
            throw new ApiException(ErrorCode.INVALID_RULE,
                    "minSamples must be positive, got " + rule.minSamples());
        }
        if (Double.isNaN(rule.threshold()) || Double.isInfinite(rule.threshold())) {
            throw new ApiException(ErrorCode.INVALID_RULE, "threshold must be a finite number");
        }
    }
}
