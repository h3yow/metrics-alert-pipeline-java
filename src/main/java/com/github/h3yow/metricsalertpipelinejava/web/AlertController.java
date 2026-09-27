package com.github.h3yow.metricsalertpipelinejava.web;

import com.github.h3yow.metricsalertpipelinejava.common.ApiException;
import com.github.h3yow.metricsalertpipelinejava.common.ErrorCode;
import com.github.h3yow.metricsalertpipelinejava.model.rule.AlertRule;
import com.github.h3yow.metricsalertpipelinejava.model.rule.ComparisonOp;
import com.github.h3yow.metricsalertpipelinejava.model.rule.RuleStatus;
import com.github.h3yow.metricsalertpipelinejava.service.AlertEngine;
import com.github.h3yow.metricsalertpipelinejava.service.MetricStore;
import com.github.h3yow.metricsalertpipelinejava.service.RuleRegistry;
import com.github.h3yow.metricsalertpipelinejava.web.dto.RegisterRuleRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 规则注册与告警判定/查询接口。 */
@RestController
@RequestMapping("/api/alerts")
public class AlertController {

    private final RuleRegistry ruleRegistry;
    private final AlertEngine alertEngine;
    private final MetricStore metricStore;

    public AlertController(RuleRegistry ruleRegistry, AlertEngine alertEngine,
                           MetricStore metricStore) {
        this.ruleRegistry = ruleRegistry;
        this.alertEngine = alertEngine;
        this.metricStore = metricStore;
    }

    @PostMapping("/rules")
    public AlertRule registerRule(@RequestBody(required = false) RegisterRuleRequest request) {
        if (request == null) {
            throw new ApiException(ErrorCode.INVALID_RULE, "request body must not be null");
        }
        if (!metricStore.isRegistered(request.metric())) {
            // 规则不允许挂到未知指标，错误码与“规则不存在”可区分。
            throw new ApiException(ErrorCode.METRIC_NOT_FOUND,
                    "metric not registered: " + request.metric());
        }
        AlertRule rule = new AlertRule(
                request.id(),
                request.metric(),
                request.windowSeconds() == null ? 60L : request.windowSeconds(),
                request.threshold() == null ? 0d : request.threshold(),
                request.operator() == null ? ComparisonOp.GT : request.operator(),
                request.requiredBreaches() == null ? 1 : request.requiredBreaches(),
                request.minSamples() == null ? 1 : request.minSamples());
        return ruleRegistry.register(rule);
    }

    @GetMapping("/rules")
    public List<AlertRule> listRules() {
        return ruleRegistry.list();
    }

    @PostMapping("/evaluate")
    public List<RuleStatus> evaluateAll() {
        return alertEngine.evaluateAll();
    }

    @PostMapping("/rules/{ruleId}/evaluate")
    public RuleStatus evaluate(@PathVariable String ruleId) {
        return alertEngine.evaluate(ruleId);
    }

    @GetMapping("/rules/{ruleId}/status")
    public RuleStatus status(@PathVariable String ruleId) {
        return alertEngine.status(ruleId);
    }
}
