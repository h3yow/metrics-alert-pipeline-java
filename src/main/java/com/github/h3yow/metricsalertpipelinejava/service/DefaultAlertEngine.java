package com.github.h3yow.metricsalertpipelinejava.service;

import com.github.h3yow.metricsalertpipelinejava.common.ApiException;
import com.github.h3yow.metricsalertpipelinejava.model.AggregationResult;
import com.github.h3yow.metricsalertpipelinejava.model.rule.AlertRule;
import com.github.h3yow.metricsalertpipelinejava.model.rule.AlertState;
import com.github.h3yow.metricsalertpipelinejava.model.rule.RuleStatus;
import com.github.h3yow.metricsalertpipelinejava.model.rule.TransitionEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 告警引擎默认实现。
 *
 * <p>判定依据：以当前时间为右端点的滑动窗口 [now-window, now) 内样本平均值与阈值
 * 比较；样本数不足 minSamples 时不出结论。</p>
 *
 * <p>抗抖与抑制：需连续 requiredBreaches 次越界才从 NORMAL/RECOVERED 进入 ALERTING；
 * ALERTING 期间持续越界只记录抑制次数、不重复触发；任一次不越界立即 RECOVERED。
 * 同规则判定在自身锁内串行，迁移记录完整有序，可追溯。</p>
 */
@Component
public class DefaultAlertEngine implements AlertEngine {

    private static final Logger log = LoggerFactory.getLogger(DefaultAlertEngine.class);

    private final RuleRegistry ruleRegistry;
    private final MetricStore metricStore;
    private final Clock clock;
    private final Map<String, RuleRuntime> runtimes = new ConcurrentHashMap<>();

    public DefaultAlertEngine(RuleRegistry ruleRegistry, MetricStore metricStore, Clock clock) {
        this.ruleRegistry = ruleRegistry;
        this.metricStore = metricStore;
        this.clock = clock;
    }

    @Override
    public List<RuleStatus> evaluateAll() {
        List<RuleStatus> out = new ArrayList<>();
        for (AlertRule rule : ruleRegistry.list()) {
            out.add(evaluate(rule.id()));
        }
        return out;
    }

    @Override
    public RuleStatus evaluate(String ruleId) {
        AlertRule rule = ruleRegistry.get(ruleId);
        RuleRuntime runtime = runtimes.computeIfAbsent(ruleId, k -> new RuleRuntime());
        long now = clock.nowMillis();
        long windowStart = now - rule.windowSeconds() * 1000L;

        AggregationResult agg;
        try {
            agg = metricStore.aggregate(rule.metric(), windowStart, now);
        } catch (ApiException ex) {
            // 规则引用了不存在的指标等：不改变状态机，直接把可判定原因交回调用方。
            log.warn("evaluate {} aborted due to store error: {}", ruleId, ex.getMessage());
            throw ex;
        }

        synchronized (runtime) {
            runtime.currentValue = agg.count() == 0L ? null : agg.average();
            runtime.evaluatedAt = now;

            if (agg.count() < rule.minSamples()) {
                // 数据不足：不出告警结论。告警中遇到数据缺口保持告警，避免用缺口伪造恢复。
                if (runtime.state == AlertState.NORMAL || runtime.state == AlertState.RECOVERED) {
                    transition(runtime, now, AlertState.INSUFFICIENT_DATA,
                            "sample count " + agg.count() + " < minSamples " + rule.minSamples(),
                            agg);
                } else {
                    log.info("rule={} state={} held; insufficient data count={} in [{},{})",
                            ruleId, runtime.state, agg.count(), windowStart, now);
                }
                return snapshot(ruleId, runtime);
            }

            boolean breached = isBreached(rule, agg.average());
            AlertState previous = runtime.state;

            switch (previous) {
                case INSUFFICIENT_DATA -> {
                    runtime.breachStreak = breached ? 1 : 0;
                    AlertState target = breached
                            ? (rule.requiredBreaches() <= 1 ? AlertState.ALERTING : AlertState.NORMAL)
                            : AlertState.NORMAL;
                    transition(runtime, now, target,
                            "sufficient data first evaluation, breached=" + breached
                                    + ", streak=" + runtime.breachStreak + ", avg=" + agg.average(),
                            agg);
                }
                case NORMAL, RECOVERED -> {
                    if (breached) {
                        runtime.breachStreak++;
                        if (runtime.breachStreak >= rule.requiredBreaches()) {
                            transition(runtime, now, AlertState.ALERTING,
                                    "breach streak " + runtime.breachStreak
                                            + " reached required " + rule.requiredBreaches()
                                            + ", avg=" + agg.average(), agg);
                        } else {
                            log.info("rule={} state={} streak={} below required={}; avg={} in [{},{})",
                                    ruleId, previous, runtime.breachStreak,
                                    rule.requiredBreaches(), agg.average(), windowStart, now);
                        }
                    } else {
                        runtime.breachStreak = 0;
                        if (previous == AlertState.RECOVERED) {
                            // 恢复后首个正常判定，收敛为稳定正常态。
                            transition(runtime, now, AlertState.NORMAL,
                                    "recovered state confirmed by non-breaching average "
                                            + agg.average(), agg);
                        }
                    }
                }
                case ALERTING -> {
                    if (breached) {
                        // 重复触发抑制：仍在告警中，只记录抑制，不产生新迁移。
                        runtime.suppressed++;
                        log.info("rule={} ALERTING held, suppressed re-trigger #{} avg={} [{},{})",
                                ruleId, runtime.suppressed, agg.average(), windowStart, now);
                    } else {
                        runtime.breachStreak = 0;
                        transition(runtime, now, AlertState.RECOVERED,
                                "first non-breaching evaluation while alerting, avg="
                                        + agg.average(), agg);
                    }
                }
            }
            return snapshot(ruleId, runtime);
        }
    }

    @Override
    public RuleStatus status(String ruleId) {
        ruleRegistry.get(ruleId);
        RuleRuntime runtime = runtimes.get(ruleId);
        if (runtime == null) {
            return new RuleStatus(ruleId, AlertState.INSUFFICIENT_DATA, null, 0L, 0, List.of());
        }
        synchronized (runtime) {
            return snapshot(ruleId, runtime);
        }
    }

    private boolean isBreached(AlertRule rule, double avg) {
        return switch (rule.operator()) {
            case GT -> avg > rule.threshold();
            case GE -> avg >= rule.threshold();
            case LT -> avg < rule.threshold();
            case LE -> avg <= rule.threshold();
        };
    }

    private void transition(RuleRuntime runtime, long now, AlertState target,
                            String reason, AggregationResult agg) {
        AlertState from = runtime.state;
        if (from == target) {
            return;
        }
        runtime.state = target;
        runtime.transitions.add(new TransitionEvent(
                now, from, target, reason,
                agg.count() == 0L ? Double.NaN : agg.average(),
                agg.windowStart(), agg.windowEnd(), agg.count()));
        log.info("ALERT TRANSITION {} -> {} at {} reason: {}", from, target, now, reason);
    }

    private RuleStatus snapshot(String ruleId, RuleRuntime runtime) {
        return new RuleStatus(ruleId, runtime.state, runtime.currentValue,
                runtime.evaluatedAt, runtime.breachStreak, List.copyOf(runtime.transitions));
    }

    /** 规则运行态：状态机变量与迁移轨迹，访问均在 synchronized(this) 下。 */
    private static final class RuleRuntime {
        private AlertState state = AlertState.INSUFFICIENT_DATA;
        private Double currentValue = null;
        private long evaluatedAt = 0L;
        private int breachStreak = 0;
        private int suppressed = 0;
        private final List<TransitionEvent> transitions = new ArrayList<>();
    }
}
