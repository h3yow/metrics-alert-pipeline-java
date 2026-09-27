package com.github.h3yow.metricsalertpipelinejava.model;

public class AlertRuleConfig {
    private String ruleId;
    private String metric;
    private AlertOperator operator;
    private double threshold;
    private long windowMillis;
    private int breachThreshold;
    private int recoverThreshold;
    private long minSamples;

    public AlertRuleConfig() {
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

    public AlertOperator getOperator() {
        return operator;
    }

    public void setOperator(AlertOperator operator) {
        this.operator = operator;
    }

    public double getThreshold() {
        return threshold;
    }

    public void setThreshold(double threshold) {
        this.threshold = threshold;
    }

    public long getWindowMillis() {
        return windowMillis;
    }

    public void setWindowMillis(long windowMillis) {
        this.windowMillis = windowMillis;
    }

    public int getBreachThreshold() {
        return breachThreshold;
    }

    public void setBreachThreshold(int breachThreshold) {
        this.breachThreshold = breachThreshold;
    }

    public int getRecoverThreshold() {
        return recoverThreshold;
    }

    public void setRecoverThreshold(int recoverThreshold) {
        this.recoverThreshold = recoverThreshold;
    }

    public long getMinSamples() {
        return minSamples;
    }

    public void setMinSamples(long minSamples) {
        this.minSamples = minSamples;
    }
}
