package com.github.h3yow.metricsalertpipelinejava.model;

public enum AlertOperator {
    GT, GTE, LT, LTE;

    public boolean test(double actual, double threshold) {
        return switch (this) {
            case GT -> actual > threshold;
            case GTE -> actual >= threshold;
            case LT -> actual < threshold;
            case LTE -> actual <= threshold;
        };
    }
}
