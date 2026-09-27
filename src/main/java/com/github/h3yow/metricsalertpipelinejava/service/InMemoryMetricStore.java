package com.github.h3yow.metricsalertpipelinejava.service;

import com.github.h3yow.metricsalertpipelinejava.common.ApiException;
import com.github.h3yow.metricsalertpipelinejava.common.ErrorCode;
import com.github.h3yow.metricsalertpipelinejava.model.AggregationResult;
import com.github.h3yow.metricsalertpipelinejava.model.IngestResponse;
import com.github.h3yow.metricsalertpipelinejava.model.IngestStatus;
import com.github.h3yow.metricsalertpipelinejava.model.Sample;
import com.github.h3yow.metricsalertpipelinejava.model.SampleResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存指标存储实现。
 *
 * <p>并发模型：指标定义放在 {@link ConcurrentHashMap}，每个指标对应一把独立锁
 * （{@link MetricData} 监视器），同指标写入串行化、跨指标互不阻塞。乱序到达时，
 * 只要时间戳不低于当前水位线即正常计入，聚合基于事件时间因此与到达顺序无关，
 * 最终状态确定一致。</p>
 *
 * <p>水位线 watermark = 已观察最大时间戳 - MAX_LATENESS_MILLIS；低于水位线的
 * 样本判定为迟到拒绝。</p>
 */
@Component
public class InMemoryMetricStore implements MetricStore {

    private static final Logger log = LoggerFactory.getLogger(InMemoryMetricStore.class);

    /** 允许的迟到毫秒数：低于当前水位线的样本拒绝。 */
    public static final long MAX_LATENESS_MILLIS = 60_000L;
    /** 允许的未来偏移：时间戳超过当前水位线参考点该值视为异常。 */
    public static final long MAX_FUTURE_MILLIS = 10_000L;
    /** 单次窗口/区间允许的最大跨度，防止越界扫描。 */
    public static final long MAX_WINDOW_MILLIS = 7L * 24 * 60 * 60 * 1000;
    /** 区间查询最多返回的桶数，超过视为越界。 */
    public static final int MAX_BUCKETS = 1000;

    private final Clock clock;
    private final Map<String, MetricData> metrics = new ConcurrentHashMap<>();

    public InMemoryMetricStore(Clock clock) {
        this.clock = clock;
    }

    @Override
    public void registerMetric(String metric) {
        if (metric == null || metric.isBlank()) {
            throw new ApiException(ErrorCode.INVALID_SAMPLE, "metric name must not be blank");
        }
        metrics.computeIfAbsent(metric, m -> new MetricData());
    }

    @Override
    public boolean isRegistered(String metric) {
        return metric != null && metrics.containsKey(metric);
    }

    @Override
    public IngestResponse ingest(List<Sample> samples) {
        if (samples == null) {
            throw new ApiException(ErrorCode.INVALID_SAMPLE, "samples must not be null");
        }
        List<SampleResult> results = new ArrayList<>(samples.size());
        int accepted = 0;
        int ignored = 0;
        int rejected = 0;

        for (Sample sample : samples) {
            SampleResult result = classify(sample);
            results.add(result);
            switch (result.status()) {
                case ACCEPTED -> accepted++;
                case DUPLICATE_IGNORED -> ignored++;
                default -> rejected++;
            }
        }
        log.info("ingest batch size={} accepted={} ignored={} rejected={}",
                samples.size(), accepted, ignored, rejected);
        return new IngestResponse(accepted, ignored, rejected, List.copyOf(results));
    }

    private SampleResult classify(Sample sample) {
        if (sample == null) {
            return new SampleResult(null, IngestStatus.INVALID_SAMPLE, "sample is null");
        }
        if (sample.metric() == null || sample.metric().isBlank()) {
            return new SampleResult(sample, IngestStatus.METRIC_UNREGISTERED,
                    "metric is null/blank");
        }
        if (sample.timestamp() <= 0L) {
            return new SampleResult(sample, IngestStatus.INVALID_TIMESTAMP,
                    "timestamp must be positive epoch millis, got " + sample.timestamp());
        }
        MetricData data = metrics.get(sample.metric());
        if (data == null) {
            return new SampleResult(sample, IngestStatus.METRIC_UNREGISTERED,
                    "metric not registered: " + sample.metric());
        }

        long now = clock.nowMillis();
        // 同指标串行：未来/重复/迟到判定与最大事件时间推进必须在同一临界区内，
        // 否则并发写入会让水位线与去重集合出现竞态。
        synchronized (data) {
            // 时间戳异常：相对处理时钟，超过容忍的未来偏移直接拒绝。
            if (sample.timestamp() > now + MAX_FUTURE_MILLIS) {
                return new SampleResult(sample, IngestStatus.FUTURE_TIMESTAMP,
                        "ts=" + sample.timestamp() + " exceeds processing now " + now
                                + " + " + MAX_FUTURE_MILLIS + "ms");
            }
            Double existed = data.points.get(sample.timestamp());
            if (existed != null) {
                // 幂等优先：完全相同的重复上报忽略；同时间戳不同值拒绝，防止覆盖。
                if (Double.compare(existed, sample.value()) == 0) {
                    return new SampleResult(sample, IngestStatus.DUPLICATE_IGNORED,
                            "identical sample already exists at ts=" + sample.timestamp());
                }
                return new SampleResult(sample, IngestStatus.CONFLICTING_VALUE,
                        "ts=" + sample.timestamp() + " already has value " + existed
                                + ", conflicting value " + sample.value());
            }
            // 迟到：相对事件时间水位线（已观察最大事件时间 - 迟到容忍）。
            if (sample.timestamp() < data.watermark()) {
                return new SampleResult(sample, IngestStatus.LATE_REJECTED,
                        "ts=" + sample.timestamp() + " is below watermark=" + data.watermark()
                                + " (max event ts=" + data.maxObservedTs
                                + ", max lateness " + MAX_LATENESS_MILLIS + "ms)");
            }
            data.points.put(sample.timestamp(), sample.value());
            data.maxObservedTs = Math.max(data.maxObservedTs, sample.timestamp());
            return new SampleResult(sample, IngestStatus.ACCEPTED,
                    "accepted at ts=" + sample.timestamp() + ", watermark=" + data.watermark());
        }
    }

    @Override
    public AggregationResult aggregate(String metric, long windowStart, long windowEnd) {
        requireMetric(metric);
        validateWindow(windowStart, windowEnd);

        MetricData data = metrics.get(metric);
        synchronized (data) {
            NavigableMap<Long, Double> slice =
                    data.points.subMap(windowStart, true, windowEnd, false);
            return toAggregation(metric, windowStart, windowEnd, slice);
        }
    }

    @Override
    public List<AggregationResult> queryRange(String metric, long from, long to, long bucketMillis) {
        requireMetric(metric);
        if (from <= 0L || to <= 0L || to <= from) {
            throw new ApiException(ErrorCode.INVALID_QUERY_RANGE,
                    "require 0 < from < to, got from=" + from + ", to=" + to);
        }
        if (bucketMillis <= 0L) {
            throw new ApiException(ErrorCode.INVALID_WINDOW,
                    "bucketMillis must be positive, got " + bucketMillis);
        }
        long span = to - from;
        if (span > MAX_WINDOW_MILLIS) {
            throw new ApiException(ErrorCode.INVALID_QUERY_RANGE,
                    "range span " + span + "ms exceeds max " + MAX_WINDOW_MILLIS + "ms");
        }
        if (span % bucketMillis != 0L) {
            throw new ApiException(ErrorCode.INVALID_WINDOW,
                    "range span must be divisible by bucketMillis (span=" + span
                            + ", bucket=" + bucketMillis + ")");
        }
        long bucketCount = span / bucketMillis;
        if (bucketCount > MAX_BUCKETS) {
            throw new ApiException(ErrorCode.INVALID_QUERY_RANGE,
                    "bucket count " + bucketCount + " exceeds max " + MAX_BUCKETS);
        }

        MetricData data = metrics.get(metric);
        List<AggregationResult> out = new ArrayList<>((int) bucketCount);
        synchronized (data) {
            for (long start = from; start < to; start += bucketMillis) {
                NavigableMap<Long, Double> slice =
                        data.points.subMap(start, true, start + bucketMillis, false);
                out.add(toAggregation(metric, start, start + bucketMillis, slice));
            }
        }
        return out;
    }

    private void requireMetric(String metric) {
        if (metric == null || metric.isBlank() || !metrics.containsKey(metric)) {
            throw new ApiException(ErrorCode.METRIC_NOT_FOUND,
                    "metric not registered: " + metric);
        }
    }

    private void validateWindow(long windowStart, long windowEnd) {
        if (windowStart <= 0L || windowEnd <= 0L || windowEnd <= windowStart) {
            throw new ApiException(ErrorCode.INVALID_WINDOW,
                    "require 0 < windowStart < windowEnd, got ["
                            + windowStart + ", " + windowEnd + ")");
        }
        if (windowEnd - windowStart > MAX_WINDOW_MILLIS) {
            throw new ApiException(ErrorCode.INVALID_WINDOW,
                    "window span exceeds max " + MAX_WINDOW_MILLIS + "ms");
        }
    }

    private AggregationResult toAggregation(String metric, long start, long end,
                                            NavigableMap<Long, Double> slice) {
        long count = slice.size();
        if (count == 0L) {
            return new AggregationResult(metric, start, end, 0L, 0d, 0d, 0d, 0d);
        }
        double sum = 0d;
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (double v : slice.values()) {
            sum += v;
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        return new AggregationResult(metric, start, end, count, sum, min, max, sum / count);
    }

    /** 单指标数据，所有字段访问均在 {@code synchronized(this)} 下进行。 */
    private static final class MetricData {
        private final TreeMap<Long, Double> points = new TreeMap<>();
        // 已观察到的最大事件时间；水位线 = maxObservedTs - MAX_LATENESS_MILLIS。
        // 初始为负无穷：首个样本接受前不限制历史。
        private long maxObservedTs = Long.MIN_VALUE;

        private long watermark() {
            return maxObservedTs == Long.MIN_VALUE
                    ? Long.MIN_VALUE : maxObservedTs - MAX_LATENESS_MILLIS;
        }
    }
}
