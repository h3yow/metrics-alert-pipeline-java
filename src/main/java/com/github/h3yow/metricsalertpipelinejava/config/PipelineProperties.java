package com.github.h3yow.metricsalertpipelinejava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "pipeline")
public class PipelineProperties {

    private long allowedLatenessMillis = 5000;
    private long maxFutureMillis = 60000;
    private int maxBatchSize = 1000;
    private int transitionHistorySize = 100;

    public long getAllowedLatenessMillis() {
        return allowedLatenessMillis;
    }

    public void setAllowedLatenessMillis(long allowedLatenessMillis) {
        this.allowedLatenessMillis = allowedLatenessMillis;
    }

    public long getMaxFutureMillis() {
        return maxFutureMillis;
    }

    public void setMaxFutureMillis(long maxFutureMillis) {
        this.maxFutureMillis = maxFutureMillis;
    }

    public int getMaxBatchSize() {
        return maxBatchSize;
    }

    public void setMaxBatchSize(int maxBatchSize) {
        this.maxBatchSize = maxBatchSize;
    }

    public int getTransitionHistorySize() {
        return transitionHistorySize;
    }

    public void setTransitionHistorySize(int transitionHistorySize) {
        this.transitionHistorySize = transitionHistorySize;
    }
}
