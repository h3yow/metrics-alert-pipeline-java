package com.github.h3yow.metricsalertpipelinejava.tests.support;

import com.github.h3yow.metricsalertpipelinejava.service.Clock;

/** 可手动推进的时间源，单位 epoch millis。 */
public class ManualClock implements Clock {

    private volatile long now;

    public ManualClock(long now) {
        this.now = now;
    }

    @Override
    public long nowMillis() {
        return now;
    }

    public void setNow(long now) {
        this.now = now;
    }

    public void advanceMillis(long delta) {
        this.now += delta;
    }
}
