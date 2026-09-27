package com.github.h3yow.metricsalertpipelinejava.web.dto;

import com.github.h3yow.metricsalertpipelinejava.model.rule.ComparisonOp;

/** 注册规则请求。 */
public record RegisterRuleRequest(
        String id,
        String metric,
        Long windowSeconds,
        Double threshold,
        ComparisonOp operator,
        Integer requiredBreaches,
        Integer minSamples) {
}
