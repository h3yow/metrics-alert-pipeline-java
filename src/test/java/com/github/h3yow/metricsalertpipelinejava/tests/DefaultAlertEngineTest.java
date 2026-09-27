package com.github.h3yow.metricsalertpipelinejava.tests;

import com.github.h3yow.metricsalertpipelinejava.common.ApiException;
import com.github.h3yow.metricsalertpipelinejava.common.ErrorCode;
import com.github.h3yow.metricsalertpipelinejava.model.Sample;
import com.github.h3yow.metricsalertpipelinejava.model.rule.AlertRule;
import com.github.h3yow.metricsalertpipelinejava.model.rule.AlertState;
import com.github.h3yow.metricsalertpipelinejava.model.rule.ComparisonOp;
import com.github.h3yow.metricsalertpipelinejava.model.rule.RuleStatus;
import com.github.h3yow.metricsalertpipelinejava.model.rule.TransitionEvent;
import com.github.h3yow.metricsalertpipelinejava.service.DefaultAlertEngine;
import com.github.h3yow.metricsalertpipelinejava.service.InMemoryMetricStore;
import com.github.h3yow.metricsalertpipelinejava.service.InMemoryRuleRegistry;
import com.github.h3yow.metricsalertpipelinejava.tests.support.ManualClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 告警引擎单测：滑动窗口判定与状态机确定性。日志包含输入样本、窗口判定依据与迁移轨迹。
 */
class DefaultAlertEngineTest {

    private static final Logger log = LoggerFactory.getLogger(DefaultAlertEngineTest.class);

    private static final long T0 = 1_000_000L;

    private ManualClock clock;
    private InMemoryMetricStore store;
    private InMemoryRuleRegistry registry;
    private DefaultAlertEngine engine;

    @BeforeEach
    void setUp() {
        clock = new ManualClock(T0);
        store = new InMemoryMetricStore(clock);
        registry = new InMemoryRuleRegistry();
        engine = new DefaultAlertEngine(registry, store, clock);
        store.registerMetric("cpu");
    }

    private AlertRule rule(long windowSeconds, int requiredBreaches, int minSamples, double threshold) {
        return new AlertRule("r1", "cpu", windowSeconds, threshold, ComparisonOp.GT,
                requiredBreaches, minSamples);
    }

    private void ingestAt(long ts, double... values) {
        List<Sample> samples = java.util.Arrays.stream(values)
                .mapToObj(v -> new Sample("cpu", ts, v))
                .toList();
        log.info("ingest ts={} values={}", ts,
                samples.stream().map(s -> String.valueOf(s.value())).collect(Collectors.joining(",")));
        store.ingest(samples);
    }

    private RuleStatus eval(String label) {
        RuleStatus st = engine.evaluate("r1");
        log.info("[{}] evaluate at now={} window=[{},{}) state={} streak={} value={} transitions={}",
                label, clock.nowMillis(), clock.nowMillis() - 60_000, clock.nowMillis(),
                st.state(), st.breachStreak(), st.currentValue(),
                st.transitions().stream()
                        .map(t -> t.from() + "->" + t.to() + "@" + t.timestamp())
                        .collect(Collectors.joining(" | ")));
        return st;
    }

    /** 推进到指定处理时间，在 now-1s 处写入样本，使样本稳定落在判定窗口内部。 */
    private void tick(long now, double... values) {
        clock.setNow(now);
        ingestAt(now - 1000, values);
    }

    @Test
    @DisplayName("数据不足保持 INSUFFICIENT_DATA，满足 minSamples 后进入 NORMAL")
    void insufficientDataDoesNotAlert() {
        registry.register(rule(60, 1, 3, 100d));

        ingestAt(T0 - 3000, 50);
        RuleStatus s1 = eval("one-sample");
        assertEquals(AlertState.INSUFFICIENT_DATA, s1.state());

        ingestAt(T0 - 2000, 60);
        ingestAt(T0 - 1000, 70);
        RuleStatus s2 = eval("three-samples");
        assertEquals(AlertState.NORMAL, s2.state());
        assertEquals(60d, s2.currentValue(), 1e-9, "窗口内为 50/60/70 三个样本");
        // 迁移轨迹确定可追溯
        assertEquals(AlertState.INSUFFICIENT_DATA, s2.transitions().get(0).from());
        assertEquals(AlertState.NORMAL, s2.transitions().get(0).to());
    }

    @Test
    @DisplayName("滑动窗口：滑出窗口的旧样本不再计入，告警可自然恢复")
    void slidingWindowExpiry() {
        registry.register(rule(60, 1, 1, 100d));

        tick(T0, 200);
        RuleStatus firing = eval("breach");
        assertEquals(AlertState.ALERTING, firing.state());

        // 时间推进超过窗口，旧的越界样本滑出窗口
        tick(T0 + 61_000, 50);
        RuleStatus recovered = eval("window-expired-normal");
        assertEquals(AlertState.RECOVERED, recovered.state());
        assertEquals(50d, recovered.currentValue(), 1e-9);

        // 恢复后再保持正常 -> NORMAL
        tick(T0 + 62_000, 50);
        RuleStatus normal = eval("confirmed-normal");
        assertEquals(AlertState.NORMAL, normal.state());
    }

    @Test
    @DisplayName("短时抖动不误报：连续越界达标才触发；告警中重复越界被抑制；恢复后可再次触发")
    void debounceSuppressAndRetrigger() {
        // 2s 滑动窗口，需连续 2 次越界、至少 1 个样本；每个 tick 推进 2s，窗口内仅最新样本
        registry.register(rule(2, 2, 1, 100d));

        tick(T0, 200);
        assertEquals(AlertState.NORMAL, eval("breach-1").state(),
                "第 1 次越界不触发，抑制短时抖动");

        tick(T0 + 2000, 50);
        assertEquals(AlertState.NORMAL, eval("normal-resets-streak").state(),
                "中途一次正常打断连续越界");

        tick(T0 + 4000, 200);
        assertEquals(AlertState.NORMAL, eval("breach-again-1").state());

        tick(T0 + 6000, 200);
        RuleStatus firing = eval("breach-again-2");
        assertEquals(AlertState.ALERTING, firing.state(), "连续 2 次越界才触发");
        int transitionCount = firing.transitions().size();

        tick(T0 + 8000, 300);
        RuleStatus stillFiring = eval("repeat-breach-suppressed");
        assertEquals(AlertState.ALERTING, stillFiring.state());
        assertEquals(transitionCount, stillFiring.transitions().size(),
                "重复触发被抑制，不产生新迁移");

        tick(T0 + 10_000, 50);
        RuleStatus recovered = eval("recover");
        assertEquals(AlertState.RECOVERED, recovered.state());

        // 恢复后再次连续越界，允许重新触发
        tick(T0 + 12_000, 200);
        assertEquals(AlertState.RECOVERED, eval("retrigger-streak-1").state());
        tick(T0 + 14_000, 200);
        RuleStatus firingAgain = eval("retrigger-streak-2");
        assertEquals(AlertState.ALERTING, firingAgain.state());

        List<TransitionEvent> path = firingAgain.transitions();
        log.info("full transition path: {}", path.stream()
                .map(t -> t.from() + "->" + t.to() + "(reason=" + t.reason() + ")")
                .collect(Collectors.joining(" | ")));
        // 期望迁移终点序列：NORMAL（数据足够）-> ALERTING -> RECOVERED -> ALERTING
        assertEquals(List.of(
                AlertState.NORMAL, AlertState.ALERTING,
                AlertState.RECOVERED, AlertState.ALERTING),
                path.stream().map(TransitionEvent::to).toList());
    }

    @Test
    @DisplayName("刚恢复时窗口中若混入迟到被拒样本，恢复判定只依据实际计入的样本")
    void lateRejectedSampleDoesNotCorruptState() {
        registry.register(rule(2, 1, 1, 100d));

        tick(T0, 200);
        assertEquals(AlertState.ALERTING, eval("firing").state());

        // 推进 2s 并继续越界：maxObservedTs=T0+2s-1s, watermark=T0-59s；窗口仅最新样本
        tick(T0 + 2000, 300);
        assertEquals(AlertState.ALERTING, eval("still-firing").state());

        // 先把事件时间推到足够远（+120s），让水位线越过 T0
        tick(T0 + 120_000, 300);
        // 迟到样本被拒绝：T0+10s 低于水位线 T0+59s，且不与已有时间戳冲突
        var late = store.ingest(List.of(new Sample("cpu", T0 + 10_000, 50)));
        assertEquals(com.github.h3yow.metricsalertpipelinejava.model.IngestStatus.LATE_REJECTED,
                late.results().get(0).status());
        log.info("late sample rejected, reason: {}", late.results().get(0).reason());

        // 在当前窗口放入正常值 -> 恢复，聚合不受迟到样本影响
        tick(T0 + 122_000, 50);
        RuleStatus recovered = eval("recover-clean");
        assertEquals(AlertState.RECOVERED, recovered.state());
        assertEquals(50d, recovered.currentValue(), 1e-9,
                "窗口仅包含当前周期计入的样本，迟到样本未污染平均值");
    }

    @Test
    @DisplayName("未知规则与未知指标返回可区分错误码，异常不透出堆栈")
    void unknownRuleAndMetric() {
        ApiException unknownRule = assertThrows(ApiException.class, () -> engine.evaluate("nope"));
        assertEquals(ErrorCode.RULE_NOT_FOUND, unknownRule.getCode());
        ApiException unknownRuleStatus = assertThrows(ApiException.class, () -> engine.status("nope"));
        assertEquals(ErrorCode.RULE_NOT_FOUND, unknownRuleStatus.getCode());

        registry.register(new AlertRule("r2", "ghost", 60L, 100d,
                ComparisonOp.GT, 1, 1));
        ApiException unknownMetric = assertThrows(ApiException.class, () -> engine.evaluate("r2"));
        assertEquals(ErrorCode.METRIC_NOT_FOUND, unknownMetric.getCode());
    }

    @Test
    @DisplayName("规则字段非法：窗口非正、连续次数为 0 等返回 INVALID_WINDOW / INVALID_RULE")
    void invalidRuleRejected() {
        ApiException badWindow = assertThrows(ApiException.class,
                () -> registry.register(new AlertRule("x", "cpu", 0L, 1d,
                        ComparisonOp.GT, 1, 1)));
        assertEquals(ErrorCode.INVALID_WINDOW, badWindow.getCode());

        ApiException badBreaches = assertThrows(ApiException.class,
                () -> registry.register(new AlertRule("x", "cpu", 60L, 1d,
                        ComparisonOp.GT, 0, 1)));
        assertEquals(ErrorCode.INVALID_RULE, badBreaches.getCode());
        assertTrue(badBreaches.getMessage().contains("requiredBreaches"));
    }
}
