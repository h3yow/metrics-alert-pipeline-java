package com.github.h3yow.metricsalertpipelinejava.tests;

import com.github.h3yow.metricsalertpipelinejava.config.PipelineProperties;
import com.github.h3yow.metricsalertpipelinejava.model.AlertOperator;
import com.github.h3yow.metricsalertpipelinejava.model.AlertRuleConfig;
import com.github.h3yow.metricsalertpipelinejava.model.AlertState;
import com.github.h3yow.metricsalertpipelinejava.model.AlertStatusView;
import com.github.h3yow.metricsalertpipelinejava.model.MetricSample;
import com.github.h3yow.metricsalertpipelinejava.service.MetricsService;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 告警状态流转：数据不足不误报、抖动不立即触发、FIRING 重复抑制、
 * 恢复阈值满足后才回到 OK、再次触发重新留痕；整个迁移链确定可追溯。
 */
class AlertStateMachineTest {

    private static final Logger log = LoggerFactory.getLogger(AlertStateMachineTest.class);

    private final TestContext ctx = TestContext.create(new PipelineProperties());
    private final MetricsService service = ctx.service;

    private AlertRuleConfig rule(String id, long window, long minSamples, int breach, int recover) {
        AlertRuleConfig c = new AlertRuleConfig();
        c.setRuleId(id);
        c.setMetric("qps");
        c.setOperator(AlertOperator.GT);
        c.setThreshold(100d);
        c.setWindowMillis(window);
        c.setMinSamples(minSamples);
        c.setBreachThreshold(breach);
        c.setRecoverThreshold(recover);
        return c;
    }

    private void ingest(long ts, double v) {
        service.ingest(List.of(new MetricSample("qps", ts, v)));
    }

    @Test
    void stateTransitions_areDeterministicAndTraceable() {
        // 窗口 50ms、样本间隔 100ms：每次评估窗口 (t-50,t] 恰好只含最新样本，判定可控
        service.registerRule(rule("r1", 50L, 1L, 2, 2));
        long base = 5_000_000L;
        log.info("[状态机] 规则 window=50ms (t-50,t] 左开右闭, minSamples=1, 连续2次越限FIRING, 连续2次恢复OK, 阈值 avg>100");
        assertEquals(AlertState.INSUFFICIENT_DATA, service.alertStatus("r1").getState(),
                "无任何数据时必须是 INSUFFICIENT_DATA");

        ingest(base, 50); // 首个可评估窗口且未越限
        AlertStatusView v = service.alertStatus("r1");
        assertEquals(AlertState.OK, v.getState());
        log.info("[t={}] value=50 未越限 -> {}（首个可评估窗口，留痕）", base, v.getState());

        ingest(base + 100, 200); // 第一次越限：抖动，不立即 FIRING
        v = service.alertStatus("r1");
        assertEquals(AlertState.OK, v.getState());
        assertEquals(1, v.getBreachStreak());
        log.info("[t={}] value=200 越限但 breachStreak=1/2 -> 保持 {}（短时抖动抑制）", base + 100, v.getState());

        ingest(base + 200, 200); // 连续第二次越限 -> FIRING
        v = service.alertStatus("r1");
        assertEquals(AlertState.FIRING, v.getState());
        log.info("[t={}] breachStreak=2/2 -> {}（触发，留痕）", base + 200, v.getState());

        int transitionsAfterFire = v.getTransitions().size();
        ingest(base + 300, 200); // 继续越限：重复触发抑制
        v = service.alertStatus("r1");
        assertEquals(AlertState.FIRING, v.getState());
        assertEquals(transitionsAfterFire, v.getTransitions().size());
        log.info("[t={}] 仍越限 -> 保持 FIRING，迁移条数仍为 {}（重复触发抑制）", base + 300, transitionsAfterFire);

        ingest(base + 400, 10); // 第一次恢复：仍 FIRING
        v = service.alertStatus("r1");
        assertEquals(AlertState.FIRING, v.getState());
        assertEquals(1, v.getRecoverStreak());
        log.info("[t={}] value=10 recoverStreak=1/2 -> 保持 FIRING（恢复同样去抖）", base + 400);

        ingest(base + 500, 10); // 第二次恢复 -> OK
        v = service.alertStatus("r1");
        assertEquals(AlertState.OK, v.getState());
        log.info("[t={}] recoverStreak=2/2 -> {}（恢复，留痕）", base + 500, v.getState());

        ingest(base + 600, 300);
        ingest(base + 700, 300); // 再次连续越限 -> 再次 FIRING
        v = service.alertStatus("r1");
        assertEquals(AlertState.FIRING, v.getState());

        log.info("[最终状态] state={}, 完整迁移链（可追溯）:", v.getState());
        v.getTransitions().forEach(t -> log.info("    t={} {} -> {} (avg={}, count={}) 原因: {}",
                t.getEventTime(), t.getFrom(), t.getTo(),
                String.format("%.1f", t.getWindowValue()), t.getSampleCount(), t.getReason()));

        List<AlertState> fromStates = v.getTransitions().stream().map(t -> t.getFrom()).toList();
        assertEquals(List.of(AlertState.INSUFFICIENT_DATA, AlertState.OK, AlertState.FIRING, AlertState.OK),
                fromStates, "迁移链必须是 INSUFFICIENT->OK->FIRING->OK->FIRING");
        assertEquals(AlertState.FIRING,
                v.getTransitions().get(v.getTransitions().size() - 1).getTo());
    }

    @Test
    void insufficientDataNeverFiresEvenWhenValueIsHigh() {
        // 窗口 50ms、样本间隔 100ms、minSamples=2：每个窗口永远只有 1 个样本
        service.registerRule(rule("r2", 50L, 2L, 1, 1));
        long base = 5_500_000L;
        for (int i = 0; i < 5; i++) {
            ingest(base + i * 100L, 999);
        }
        AlertStatusView v = service.alertStatus("r2");
        log.info("[数据不足] 连续5个超高值但每窗口count=1<minSamples=2 -> state={}, streak={}",
                v.getState(), v.getBreachStreak());
        assertEquals(AlertState.INSUFFICIENT_DATA, v.getState());
        assertEquals(0, v.getBreachStreak(), "数据不足不得累积越限计数");
        assertEquals(0, v.getTransitions().size(), "未发生过状态迁移，无 FIRING 记录");
    }

    @Test
    void outOfOrderEvaluationIsIdempotent() {
        service.registerRule(rule("r3", 1000L, 1L, 1, 1));
        long base = 6_000_000L;
        ingest(base + 500, 200); // 晚事件时间先到：评估点=maxTimestamp=base+500，窗口含该样本 -> FIRING
        AlertState afterLate = service.alertStatus("r3").getState();
        ingest(base + 100, 50);  // 更早事件时间后补（允许迟到内）：评估点仍是 base+500，状态不后退
        AlertStatusView view = service.alertStatus("r3");
        log.info("[乱序评估] t={}先到 state={}; t={}后到（更早，允许迟到内）评估点仍为指标maxTimestamp -> state={}, lastEvaluated={}",
                base + 500, afterLate, base + 100, view.getState(), view.getLastEvaluatedEventTime());
        assertEquals(AlertState.FIRING, afterLate);
        assertEquals(afterLate, view.getState(), "更早事件时间的补报不得回退状态机");
        assertEquals(base + 500, view.getLastEvaluatedEventTime(), "评估点始终是指标当前最大事件时间");
    }
}
