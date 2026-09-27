package com.github.h3yow.metricsalertpipelinejava.model;

public class AggregationWindow {
    private long windowStart;
    private long windowEnd;
    private long count;
    private double min;
    private double max;
    private double sum;
    private double avg;

    public AggregationWindow() {
    }

    public AggregationWindow(long windowStart, long windowEnd, long count, double min, double max, double sum, double avg) {
        this.windowStart = windowStart;
        this.windowEnd = windowEnd;
        this.count = count;
        this.min = min;
        this.max = max;
        this.sum = sum;
        this.avg = avg;
    }

    public long getWindowStart() {
        return windowStart;
    }

    public void setWindowStart(long windowStart) {
        this.windowStart = windowStart;
    }

    public long getWindowEnd() {
        return windowEnd;
    }

    public void setWindowEnd(long windowEnd) {
        this.windowEnd = windowEnd;
    }

    public long getCount() {
        return count;
    }

    public void setCount(long count) {
        this.count = count;
    }

    public double getMin() {
        return min;
    }

    public void setMin(double min) {
        this.min = min;
    }

    public double getMax() {
        return max;
    }

    public void setMax(double max) {
        this.max = max;
    }

    public double getSum() {
        return sum;
    }

    public void setSum(double sum) {
        this.sum = sum;
    }

    public double getAvg() {
        return avg;
    }

    public void setAvg(double avg) {
        this.avg = avg;
    }
}
