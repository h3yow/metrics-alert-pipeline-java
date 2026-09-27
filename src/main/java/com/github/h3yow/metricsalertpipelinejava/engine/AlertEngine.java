package com.github.h3yow.metricsalertpipelinejava.engine;

import com.github.h3yow.metricsalertpipelinejava.alert.AlertRuleRuntime;
import com.github.h3yow.metricsalertpipelinejava.config.PipelineProperties;
import com.github.h3yow.metricsalertpipelinejava.exception.ApiErrorCode;
import com.github.h3yow.metricsalertpipelinejava.exception.ApiException;
import com.github.h3yow.metricsalertpipelinejava.model.AggregationWindow;
import com.github.h3yow.metricsalertpipelinejava.model.AlertRuleConfig;
import com.github.h3yow.metricsalertpipelinejava.model.AlertStatusView;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 告警引擎：维护规则注册表，并在每有样本被接受后驱动该指标下所有规则的状态机。
 * 规则评估使用事件时间滑动窗口；同一指标的接受写入经 MetricSeries 锁串行化，
 * 本引擎再按规则运行时自身的锁串行，保证乱序/并发下最终状态确定一致。
 */
@Component
public class AlertEngine {

    private final ConcurrentHashMap<String, AlertRuleRuntime> runtimes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<AlertRuleRuntime>> runtimesByMetric = new ConcurrentHashMap<>();
    private final PipelineProperties properties;
    private final ObjectProvider<AggregationEngine> aggregationEngineProvider;

    public AlertEngine(PipelineProperties properties, ObjectProvider<AggregationEngine> aggregationEngineProvider) {
        this.properties = properties;
        this.aggregationEngineProvider = aggregationEngineProvider;
    }

    /**
     * 注册规则。配置非法时抛出 {@link ApiException#INVALID_RULE}；
     * ruleId 重复也视为非法配置（不静默覆盖）。
     */
    public AlertRuleRuntime registerRule(AlertRuleConfig config) {
        validate(config);
        AlertRuleRuntime runtime = new AlertRuleRuntime(config, properties.getTransitionHistorySize());
        AlertRuleRuntime existing = runtimes.putIfAbsent(config.getRuleId(), runtime);
        if (existing != null) {
            throw new ApiException(ApiErrorCode.INVALID_RULE,
                    "ruleId '" + config.getRuleId() + "' already exists");
        }
        runtimesByMetric.computeIfAbsent(config.getMetric(), m -> new ArrayList<>()).add(runtime);
        return runtime;
    }

    private void validate(AlertRuleConfig c) {
        if (c == null) {
            throw new ApiException(ApiErrorCode.INVALID_PAYLOAD, "rule body is required");
        }
        List<String> problems = new ArrayList<>();
        if (c.getRuleId() == null || c.getRuleId().isBlank()) {
            problems.add("ruleId must not be blank");
        }
        if (c.getMetric() == null || c.getMetric().isBlank()) {
            problems.add("metric must not be blank");
        }
        if (c.getOperator() == null) {
            problems.add("operator must be one of GT,GTE,LT,LTE");
        }
        if (c.getWindowMillis() <= 0) {
            problems.add("windowMillis must be positive, got " + c.getWindowMillis());
        }
        if (c.getBreachThreshold() <= 0) {
            problems.add("breachThreshold must be positive, got " + c.getBreachThreshold());
        }
        if (c.getRecoverThreshold() <= 0) {
            problems.add("recoverThreshold must be positive, got " + c.getRecoverThreshold());
        }
        if (c.getMinSamples() <= 0) {
            problems.add("minSamples must be positive, got " + c.getMinSamples());
        }
        if (!problems.isEmpty()) {
            throw new ApiException(ApiErrorCode.INVALID_RULE, String.join("; ", problems));
        }
    }

    /**
     * 样本被接受后调用：以该指标<b>当前最大事件时间</b>评估其下所有规则。
     *
     * <p>乱序到达时，刚写入的样本未必是最新事件时间；以 maxTimestamp 为评估点，
     * 等价于"按当前水位线视角重放最新窗口"，保证最终状态只取决于已接受数据集合，
     * 与到达顺序无关。评估时间单调不减，运行时对同一/更早评估点幂等忽略。</p>
     */
    public void onAcceptedSample(String metric, long metricMaxTimestamp) {
        List<AlertRuleRuntime> list = runtimesByMetric.get(metric);
        if (list == null || list.isEmpty()) {
            return;
        }
        AggregationEngine engine = aggregationEngineProvider.getIfAvailable();
        if (engine == null) {
            return;
        }
        for (AlertRuleRuntime runtime : list) {
            AggregationWindow window =
                    engine.slidingAt(metric, metricMaxTimestamp, runtime.getConfig().getWindowMillis());
            runtime.evaluate(metricMaxTimestamp, window);
        }
    }

    public AlertStatusView statusOf(String ruleId) {
        AlertRuleRuntime runtime = runtimes.get(ruleId);
        if (runtime == null) {
            throw new ApiException(ApiErrorCode.UNKNOWN_RULE, "no rule with id '" + ruleId + "'");
        }
        return toView(runtime);
    }

    public List<AlertStatusView> statusOfMetric(String metric) {
        List<AlertRuleRuntime> list = runtimesByMetric.get(metric);
        List<AlertStatusView> out = new ArrayList<>();
        if (list != null) {
            for (AlertRuleRuntime runtime : list) {
                out.add(toView(runtime));
            }
        }
        return out;
    }

    public List<AlertStatusView> allStatus() {
        List<AlertStatusView> out = new ArrayList<>();
        for (Map.Entry<String, AlertRuleRuntime> e : runtimes.entrySet()) {
            out.add(toView(e.getValue()));
        }
        return out;
    }

    private AlertStatusView toView(AlertRuleRuntime runtime) {
        AlertRuleConfig c = runtime.getConfig();
        AlertStatusView view = new AlertStatusView();
        view.setRuleId(c.getRuleId());
        view.setMetric(c.getMetric());
        view.setState(runtime.getState());
        view.setLastEvaluatedEventTime(runtime.getLastEvaluatedEventTime());
        view.setLastWindowValue(runtime.getLastWindowValue());
        view.setLastSampleCount(runtime.getLastSampleCount());
        view.setBreachStreak(runtime.getBreachStreak());
        view.setRecoverStreak(runtime.getRecoverStreak());
        view.setTransitions(runtime.getTransitions());
        return view;
    }
}
