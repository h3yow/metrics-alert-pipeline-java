package com.github.h3yow.metricsalertpipelinejava.service;

import org.springframework.stereotype.Component;

/** 默认墙钟实现，读取系统当前时间（epoch millis）。 */
@Component
public class SystemClock implements Clock {
    @Override
    public long nowMillis() {
        return System.currentTimeMillis();
    }
}
