package com.github.h3yow.metricsalertpipelinejava.service;

/** 可替换的时间源，便于测试注入固定时间。 */
public interface Clock {
    long nowMillis();
}
