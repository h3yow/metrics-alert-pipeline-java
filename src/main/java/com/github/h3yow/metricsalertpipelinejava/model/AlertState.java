package com.github.h3yow.metricsalertpipelinejava.model;

/**
 * 告警规则的状态机。
 */
public enum AlertState {
    INSUFFICIENT_DATA,
    OK,
    FIRING
}
