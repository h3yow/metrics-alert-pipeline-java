package com.github.h3yow.metricsalertpipelinejava.model;

import java.util.List;

public class AlertStatusView {
    private String ruleId;
    private String metric;
    private AlertState state;
    private long lastEvaluatedEventTime;
    private Double lastWindowValue;
    private Long lastSampleCount;
    private int breachStreak;
    private int recoverStreak;
    private List<AlertTransition> transitions;

    public AlertStatusView() {
    }

    public String getRuleId() {
        return ruleId;
    }

    public void setRuleId(String ruleId) {
        this.ruleId = ruleId;
    }

    public String getMetric() {
        return metric;
    }

    public void setMetric(String metric) {
        this.metric = metric;
    }

    public AlertState getState() {
        return state;
    }

    public void setState(AlertState state) {
        this.state = state;
    }

    public long getLastEvaluatedEventTime() {
        return lastEvaluatedEventTime;
    }

    public void setLastEvaluatedEventTime(long lastEvaluatedEventTime) {
        this.lastEvaluatedEventTime = lastEvaluatedEventTime;
    }

    public Double getLastWindowValue() {
        return lastWindowValue;
    }

    public void setLastWindowValue(Double lastWindowValue) {
        this.lastWindowValue = lastWindowValue;
    }

    public Long getLastSampleCount() {
        return lastSampleCount;
    }

    public void setLastSampleCount(Long lastSampleCount) {
        this.lastSampleCount = lastSampleCount;
    }

    public int getBreachStreak() {
        return breachStreak;
    }

    public void setBreachStreak(int breachStreak) {
        this.breachStreak = breachStreak;
    }

    public int getRecoverStreak() {
        return recoverStreak;
    }

    public void setRecoverStreak(int recoverStreak) {
        this.recoverStreak = recoverStreak;
    }

    public List<AlertTransition> getTransitions() {
        return transitions;
    }

    public void setTransitions(List<AlertTransition> transitions) {
        this.transitions = transitions;
    }
}
