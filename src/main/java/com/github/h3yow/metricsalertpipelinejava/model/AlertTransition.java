package com.github.h3yow.metricsalertpipelinejava.model;

public class AlertTransition {
    private long eventTime;
    private AlertState from;
    private AlertState to;
    private double windowValue;
    private long sampleCount;
    private String reason;

    public AlertTransition() {
    }

    public AlertTransition(long eventTime, AlertState from, AlertState to, double windowValue, long sampleCount, String reason) {
        this.eventTime = eventTime;
        this.from = from;
        this.to = to;
        this.windowValue = windowValue;
        this.sampleCount = sampleCount;
        this.reason = reason;
    }

    public long getEventTime() {
        return eventTime;
    }

    public void setEventTime(long eventTime) {
        this.eventTime = eventTime;
    }

    public AlertState getFrom() {
        return from;
    }

    public AlertState getTo() {
        return to;
    }

    public double getWindowValue() {
        return windowValue;
    }

    public void setWindowValue(double windowValue) {
        this.windowValue = windowValue;
    }

    public long getSampleCount() {
        return sampleCount;
    }

    public void setSampleCount(long sampleCount) {
        this.sampleCount = sampleCount;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}
