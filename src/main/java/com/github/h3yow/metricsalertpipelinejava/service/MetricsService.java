package com.github.h3yow.metricsalertpipelinejava.service;

import com.github.h3yow.metricsalertpipelinejava.alert.AlertRuleRuntime;
import com.github.h3yow.metricsalertpipelinejava.config.PipelineProperties;
import com.github.h3yow.metricsalertpipelinejava.engine.AggregationEngine;
import com.github.h3yow.metricsalertpipelinejava.engine.AlertEngine;
import com.github.h3yow.metricsalertpipelinejava.exception.ApiErrorCode;
import com.github.h3yow.metricsalertpipelinejava.exception.ApiException;
import com.github.h3yow.metricsalertpipelinejava.model.AggregationWindow;
import com.github.h3yow.metricsalertpipelinejava.model.AlertRuleConfig;
import com.github.h3yow.metricsalertpipelinejava.model.AlertStatusView;
import com.github.h3yow.metricsalertpipelinejava.model.IngestResponse;
import com.github.h3yow.metricsalertpipelinejava.model.IngestStatus;
import com.github.h3yow.metricsalertpipelinejava.model.MetricSample;
import com.github.h3yow.metricsalertpipelinejava.model.SampleResult;
import com.github.h3yow.metricsalertpipelinejava.store.InMemoryMetricStore;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 应用服务：编排存储、聚合与告警引擎，并对外层输入做统一校验。
 * 校验失败一律以 {@link ApiException} 携带可区分的 {@link ApiErrorCode} 抛出，
 * 内部异常不直接透出给调用方。
 */
@Service
public class MetricsService {

    private final InMemoryMetricStore store;
    private final AggregationEngine aggregationEngine;
    private final AlertEngine alertEngine;
    private final PipelineProperties properties;

    public MetricsService(InMemoryMetricStore store, AggregationEngine aggregationEngine,
                          AlertEngine alertEngine, PipelineProperties properties) {
        this.store = store;
        this.aggregationEngine = aggregationEngine;
        this.alertEngine = alertEngine;
        this.properties = properties;
    }

    /**
     * 批量接入：逐条返回可判定结果（ACCEPTED/DUPLICATE/TOO_LATE/FUTURE_TIMESTAMP/
     * INVALID_TIMESTAMP），拒绝与忽略不会静默发生。
     */
    public IngestResponse ingest(List<MetricSample> samples) {
        if (samples == null || samples.isEmpty()) {
            throw new ApiException(ApiErrorCode.EMPTY_BATCH, "sample batch must contain at least one sample");
        }
        if (samples.size() > properties.getMaxBatchSize()) {
            throw new ApiException(ApiErrorCode.ILLEGAL_WINDOW,
                    "batch size " + samples.size() + " exceeds max " + properties.getMaxBatchSize());
        }
        long now = System.currentTimeMillis();
        List<SampleResult> results = new ArrayList<>(samples.size());
        int accepted = 0;
        int rejected = 0;
        for (MetricSample sample : samples) {
            if (sample == null) {
                throw new ApiException(ApiErrorCode.INVALID_PAYLOAD, "sample entries must not be null");
            }
            SampleResult result = store.ingest(sample, now,
                    properties.getAllowedLatenessMillis(), properties.getMaxFutureMillis());
            results.add(result);
            if (result.getStatus() == IngestStatus.ACCEPTED) {
                accepted++;
                // 以指标当前最大事件时间驱动告警评估（乱序到达也指向最新事件时间，幂等单调）
                alertEngine.onAcceptedSample(sample.getMetric(), store.getSeries(sample.getMetric()).getMaxTimestamp());
            } else {
                rejected++;
            }
        }
        return new IngestResponse(accepted, rejected, results);
    }

    /**
     * 滚动窗口聚合查询。失败原因可区分：未知指标 UNKNOWN_METRIC、
     * 窗口参数非法 ILLEGAL_WINDOW、查询范围越界 OUT_OF_RANGE。
     */
    public List<AggregationWindow> query(String metric, Long from, Long to, Long windowMillis) {
        if (metric == null || metric.isBlank()) {
            throw new ApiException(ApiErrorCode.UNKNOWN_METRIC, "metric name must not be blank");
        }
        if (!store.hasSeries(metric)) {
            throw new ApiException(ApiErrorCode.UNKNOWN_METRIC,
                    "no samples ever ingested for metric '" + metric + "'");
        }
        if (windowMillis == null || windowMillis <= 0) {
            throw new ApiException(ApiErrorCode.ILLEGAL_WINDOW,
                    "windowMillis must be a positive epoch-milli duration");
        }
        if (from == null || to == null) {
            throw new ApiException(ApiErrorCode.OUT_OF_RANGE,
                    "both 'from' and 'to' query bounds are required");
        }
        if (from <= 0 || to <= 0) {
            throw new ApiException(ApiErrorCode.OUT_OF_RANGE,
                    "'from' and 'to' must be positive epoch-milli values");
        }
        if (to <= from) {
            throw new ApiException(ApiErrorCode.OUT_OF_RANGE,
                    "'to' (" + to + ") must be greater than 'from' (" + from + ")");
        }
        long windowCount = (to - from) / windowMillis + 2;
        if (windowCount > AggregationEngine.MAX_WINDOWS_PER_QUERY) {
            throw new ApiException(ApiErrorCode.ILLEGAL_WINDOW,
                    "request would produce " + windowCount + " windows, exceeds limit "
                            + AggregationEngine.MAX_WINDOWS_PER_QUERY
                            + "; enlarge windowMillis or narrow the range");
        }
        return aggregationEngine.tumblingQuery(metric, from, to, windowMillis);
    }

    public AlertStatusView registerRule(AlertRuleConfig config) {
        AlertRuleRuntime runtime = alertEngine.registerRule(config);
        // 规则可能注册于数据到达之后：以该指标当前最新事件时间做一次引导评估，
        // 使规则状态反映当前窗口（仅评估最新一个滑动窗口，连续计数从注册点起累积）。
        var series = store.getSeries(config.getMetric());
        if (series != null && series.getMaxTimestamp() != Long.MIN_VALUE) {
            alertEngine.onAcceptedSample(config.getMetric(), series.getMaxTimestamp());
        }
        return alertEngine.statusOf(runtime.getConfig().getRuleId());
    }

    public AlertStatusView alertStatus(String ruleId) {
        if (ruleId == null || ruleId.isBlank()) {
            throw new ApiException(ApiErrorCode.UNKNOWN_RULE, "ruleId must not be blank");
        }
        return alertEngine.statusOf(ruleId);
    }

    public List<AlertStatusView> alertStatusByMetric(String metric) {
        if (metric == null || metric.isBlank()) {
            throw new ApiException(ApiErrorCode.UNKNOWN_METRIC, "metric name must not be blank");
        }
        if (!store.hasSeries(metric)) {
            throw new ApiException(ApiErrorCode.UNKNOWN_METRIC,
                    "no samples ever ingested for metric '" + metric + "'");
        }
        return alertEngine.statusOfMetric(metric);
    }

    public List<AlertStatusView> allAlertStatus() {
        return alertEngine.allStatus();
    }
}
