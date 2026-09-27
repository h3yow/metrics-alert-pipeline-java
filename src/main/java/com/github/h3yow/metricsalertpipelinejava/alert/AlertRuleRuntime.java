package com.github.h3yow.metricsalertpipelinejava.alert;

import com.github.h3yow.metricsalertpipelinejava.model.AggregationWindow;
import com.github.h3yow.metricsalertpipelinejava.model.AlertRuleConfig;
import com.github.h3yow.metricsalertpipelinejava.model.AlertState;
import com.github.h3yow.metricsalertpipelinejava.model.AlertTransition;

import java.util.ArrayList;
import java.util.List;

/**
 * 单条规则的状态机，状态迁移确定且可追溯：
 *
 * <pre>
 *                  首个可评估窗口
 *   INSUFFICIENT_DATA ───────────────► OK / FIRING
 *           ▲   ▲                         │  │
 *           │   └──── 数据不足(count&lt;minSamples) ─┘  │
 *           │                              ▼  ▼
 *           └──────────────────────── FIRING（连续 breachThreshold 次越限）
 *                  连续 recoverThreshold 次未越限 ──► OK
 * </pre>
 *
 * <ul>
 *   <li>短时抖动抑制：必须连续 breachThreshold 个可评估窗口越限才 FIRING；
 *       FIRING 后必须连续 recoverThreshold 个窗口恢复才回到 OK；</li>
 *   <li>重复触发抑制：FIRING 状态下继续越限不产生新的迁移记录；</li>
 *   <li>幂等乱序：eventTime 不大于上次评估时间的调用直接忽略，结果与调用顺序无关；</li>
 *   <li>数据不足：不计入任何连续计数，不产生告警，必要时迁移到 INSUFFICIENT_DATA 并留痕。</li>
 * </ul>
 */
public class AlertRuleRuntime {

    private final AlertRuleConfig config;
    private final int historySize;

    private AlertState state = AlertState.INSUFFICIENT_DATA;
    private int breachStreak = 0;
    private int recoverStreak = 0;
    private long lastEvaluatedEventTime = Long.MIN_VALUE;
    private Double lastWindowValue = null;
    private Long lastSampleCount = null;
    private final List<AlertTransition> transitions = new ArrayList<>();

    public AlertRuleRuntime(AlertRuleConfig config, int historySize) {
        this.config = config;
        this.historySize = historySize;
    }

    public AlertRuleConfig getConfig() {
        return config;
    }

    /**
     * 以评估时间 eventTime 处的滑动窗口聚合结果推进状态机。
     *
     * <p>同一 eventTime 可能因乱序重放被多次调用：仅刷新窗口视图与状态（数据可能因迟到而变多），
     * 不重复累加连续计数；更早 eventTime 的调用直接忽略。连续计数严格按评估时间单调推进。</p>
     */
    public synchronized AlertState evaluate(long eventTime, AggregationWindow window) {
        if (eventTime < lastEvaluatedEventTime) {
            return state;
        }
        long count = window == null ? 0 : window.getCount();
        lastSampleCount = count;
        lastWindowValue = window == null ? null : window.getAvg();
        if (eventTime == lastEvaluatedEventTime) {
            // 同一评估点重放：仅用最新窗口结果重新判定一次状态，不改变连续计数。
            return reevaluateAtSamePoint(window, count);
        }
        lastEvaluatedEventTime = eventTime;

        if (count < config.getMinSamples()) {
            // 数据不足：连续计数清零，不产生告警。
            breachStreak = 0;
            recoverStreak = 0;
            if (state != AlertState.INSUFFICIENT_DATA) {
                transition(eventTime, AlertState.INSUFFICIENT_DATA,
                        window == null ? 0d : window.getAvg(), count,
                        "insufficient samples: count=" + count + " < minSamples=" + config.getMinSamples());
            }
            return state;
        }

        double avg = window.getAvg();
        boolean breaching = config.getOperator().test(avg, config.getThreshold());

        if (breaching) {
            breachStreak++;
            recoverStreak = 0;
            if (state == AlertState.FIRING) {
                // 重复触发抑制：保持 FIRING，不新增迁移记录。
                return state;
            }
            if (breachStreak >= config.getBreachThreshold()) {
                transition(eventTime, AlertState.FIRING, avg, count,
                        "breach streak " + breachStreak + " >= " + config.getBreachThreshold()
                                + ": avg=" + avg + " " + config.getOperator() + " " + config.getThreshold());
            } else if (state == AlertState.INSUFFICIENT_DATA) {
                // 首个可评估窗口已给出判定依据，但抖动计数未满：先进入 OK 等待继续观察。
                transition(eventTime, AlertState.OK, avg, count,
                        "first evaluable window; breach streak " + breachStreak + "/"
                                + config.getBreachThreshold() + " not reached");
            }
            return state;
        }

        recoverStreak++;
        breachStreak = 0;
        if (state == AlertState.FIRING && recoverStreak >= config.getRecoverThreshold()) {
            transition(eventTime, AlertState.OK, avg, count,
                    "recover streak " + recoverStreak + " >= " + config.getRecoverThreshold()
                            + ": avg=" + avg + " " + config.getOperator() + " " + config.getThreshold());
        } else if (state == AlertState.INSUFFICIENT_DATA) {
            transition(eventTime, AlertState.OK, avg, count,
                    "first evaluable window: avg=" + avg + " not breaching threshold "
                            + config.getOperator() + " " + config.getThreshold());
        }
        return state;
    }

    /**
     * 同一评估事件时间的乱序重放：迟到但仍在允许窗口内的样本可能使该窗口数据变充分，
     * 因此基于当前计数与当前 streak 重新判定一次状态；不产生重复的连续计数。
     */
    private AlertState reevaluateAtSamePoint(AggregationWindow window, long count) {
        if (state != AlertState.INSUFFICIENT_DATA) {
            return state; // 已离开初始态：状态由后续更大的评估时间推进，此处不回改
        }
        if (count < config.getMinSamples()) {
            return state;
        }
        double avg = window.getAvg();
        boolean breaching = config.getOperator().test(avg, config.getThreshold());
        if (breaching) {
            breachStreak = 1;
            if (breachStreak >= config.getBreachThreshold()) {
                transition(lastEvaluatedEventTime, AlertState.FIRING, avg, count,
                        "late arrival completed the window; breach streak "
                                + breachStreak + "/" + config.getBreachThreshold());
            } else {
                transition(lastEvaluatedEventTime, AlertState.OK, avg, count,
                        "late arrival completed the window; breach streak "
                                + breachStreak + "/" + config.getBreachThreshold() + " not reached");
            }
        } else {
            recoverStreak = 1;
            transition(lastEvaluatedEventTime, AlertState.OK, avg, count,
                    "late arrival completed the window: avg=" + avg + " not breaching");
        }
        return state;
    }

    private void transition(long eventTime, AlertState to, double windowValue, long sampleCount, String reason) {
        AlertState from = state;
        state = to;
        transitions.add(new AlertTransition(eventTime, from, to, windowValue, sampleCount, reason));
        while (transitions.size() > historySize) {
            transitions.remove(0);
        }
    }

    public synchronized AlertState getState() {
        return state;
    }

    public synchronized int getBreachStreak() {
        return breachStreak;
    }

    public synchronized int getRecoverStreak() {
        return recoverStreak;
    }

    public synchronized long getLastEvaluatedEventTime() {
        return lastEvaluatedEventTime;
    }

    public synchronized Double getLastWindowValue() {
        return lastWindowValue;
    }

    public synchronized Long getLastSampleCount() {
        return lastSampleCount;
    }

    public synchronized List<AlertTransition> getTransitions() {
        return List.copyOf(transitions);
    }
}
