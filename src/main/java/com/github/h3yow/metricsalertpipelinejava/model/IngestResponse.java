package com.github.h3yow.metricsalertpipelinejava.model;

import java.util.List;

public class IngestResponse {
    private int acceptedCount;
    private int rejectedCount;
    private List<SampleResult> results;

    public IngestResponse() {
    }

    public IngestResponse(int acceptedCount, int rejectedCount, List<SampleResult> results) {
        this.acceptedCount = acceptedCount;
        this.rejectedCount = rejectedCount;
        this.results = results;
    }

    public int getAcceptedCount() {
        return acceptedCount;
    }

    public void setAcceptedCount(int acceptedCount) {
        this.acceptedCount = acceptedCount;
    }

    public int getRejectedCount() {
        return rejectedCount;
    }

    public void setRejectedCount(int rejectedCount) {
        this.rejectedCount = rejectedCount;
    }

    public List<SampleResult> getResults() {
        return results;
    }

    public void setResults(List<SampleResult> results) {
        this.results = results;
    }
}
