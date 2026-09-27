package com.github.h3yow.metricsalertpipelinejava.service;

import com.github.h3yow.metricsalertpipelinejava.model.AggregationResult;
import com.github.h3yow.metricsalertpipelinejava.model.IngestResponse;
import com.github.h3yow.metricsalertpipelinejava.model.Sample;

import java.util.List;

/**
 * 指标内存存储：登记指标、批量接入样本（处理重复/迟到/异常）、时间窗聚合、
 * 区间查询。并发写入须保证每个指标内部的最终状态一致。
 */
public interface MetricStore {

    /** 登记一个可写入/可查询的指标。 */
    void registerMetric(String metric);

    /** 指标是否已登记。 */
    boolean isRegistered(String metric);

    /** 批量接入样本，逐条给出可区分的结论。 */
    IngestResponse ingest(List<Sample> samples);

    /**
     * 对指定指标做 [windowStart, windowEnd) 左闭右开窗口聚合。
     * 窗口参数非法或指标未知时抛出 ApiException。
     */
    AggregationResult aggregate(String metric, long windowStart, long windowEnd);

    /**
     * 读取 [from, to) 区间内对齐到 bucketMillis 的窗口聚合列表。
     * 越界/非法参数抛出 ApiException。
     */
    List<AggregationResult> queryRange(String metric, long from, long to, long bucketMillis);
}
