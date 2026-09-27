package com.github.h3yow.metricsalertpipelinejava.model;

public class SampleResult {
    private String metric;
    private long timestamp;
    private IngestStatus status;
    private String reason;

    public SampleResult() {
    }

    public SampleResult(String metric, long timestamp, IngestStatus status, String reason) {
        this.metric = metric;
        this.timestamp = timestamp;
        this.status = status;
        this.reason = reason;
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

    public IngestStatus getStatus() {
        return status;
    }

    public void setStatus(IngestStatus status) {
        this.status = status;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}
