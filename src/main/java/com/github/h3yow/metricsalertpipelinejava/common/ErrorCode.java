package com.github.h3yow.metricsalertpipelinejava.common;

/**
 * 对外可判定的失败原因分类。每一类都对应稳定的错误码，调用方据此分支处理，
 * 不会看到内部异常堆栈。
 */
public enum ErrorCode {
    /** 查询/写入引用了未登记的指标 */
    METRIC_NOT_FOUND,
    /** 引用了不存在的规则 */
    RULE_NOT_FOUND,
    /** 窗口参数非法（非正、越界、溢出等） */
    INVALID_WINDOW,
    /** 区间查询参数非法（负数、起点大于终点等） */
    INVALID_QUERY_RANGE,
    /** 规则定义非法 */
    INVALID_RULE,
    /** 单条样本本身非法（指标名为空等） */
    INVALID_SAMPLE,
    /** 兜底：服务内部错误，不透出细节 */
    INTERNAL_ERROR
}
