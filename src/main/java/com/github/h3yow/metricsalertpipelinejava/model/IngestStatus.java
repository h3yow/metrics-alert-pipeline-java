package com.github.h3yow.metricsalertpipelinejava.model;

/**
 * 单条样本接入结论。ACCEPTED 之外的状态彼此可区分：
 * 重复上报是“忽略”（幂等），迟到/异常是“拒绝”。
 */
public enum IngestStatus {
    /** 正常计入 */
    ACCEPTED,
    /** 重复上报（同指标同时间戳同值），幂等忽略 */
    DUPLICATE_IGNORED,
    /** 同时间戳已存在但取值不同，拒绝 */
    CONFLICTING_VALUE,
    /** 迟到超过允许窗口（低于水位线），拒绝 */
    LATE_REJECTED,
    /** 时间戳非法（非正数等），拒绝 */
    INVALID_TIMESTAMP,
    /** 时间戳超出允许的未来偏移，拒绝 */
    FUTURE_TIMESTAMP,
    /** 写入引用了未登记的指标，拒绝 */
    METRIC_UNREGISTERED,
    /** 样本对象本身非法（null），拒绝 */
    INVALID_SAMPLE
}
