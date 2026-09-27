package com.github.h3yow.metricsalertpipelinejava.model;

public class MetricSample {
    private String metric;
    private long timestamp;
    private double value;

    public MetricSample() {
    }

    public MetricSample(String metric, long timestamp, double value) {
        this.metric = metric;
        this.timestamp = timestamp;
        this.value = value;
    }

    public String getMetric() {
        return metric;
    }

    public void setMetric(String metric) {
        this.metric = metric;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public double getValue() {
        return value;
    }

    public void setValue(double value) {
        this.value = value;
    }
}
