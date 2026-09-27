package com.github.h3yow.metricsalertpipelinejava.store;

import com.github.h3yow.metricsalertpipelinejava.model.IngestStatus;
import com.github.h3yow.metricsalertpipelinejava.model.MetricSample;

import java.util.ArrayList;
import java.util.Map;
import java.util.TreeMap;

/**
 * 单个指标的内存时序存储。所有方法均在持有本对象监视器锁的情况下访问内部结构。
 *
 * <p>时间戳为主键：同一时间戳重复写入判定为 {@link IngestStatus#DUPLICATE}。
 * 水位线 watermark = 已观察到的最大时间戳 - 允许迟到量；早于水位线的样本判定为
 * {@link IngestStatus#TOO_LATE}。迟到样本不会静默计入。</p>
 */
public class MetricSeries {

    private final String metric;
    private final TreeMap<Long, Double> points = new TreeMap<>();
    private long maxTimestamp = Long.MIN_VALUE;
    private final long allowedLatenessMillis;

    public MetricSeries(String metric, long allowedLatenessMillis) {
        this.metric = metric;
        this.allowedLatenessMillis = allowedLatenessMillis;
    }

    public String getMetric() {
        return metric;
    }

    /**
     * 尝试写入一个已通过基础校验（时间戳合法、非未来时间）的样本。
     *
     * @return ACCEPTED / DUPLICATE / TOO_LATE
     */
    public synchronized IngestStatus insert(MetricSample sample) {
        long ts = sample.getTimestamp();
        if (points.containsKey(ts)) {
            return IngestStatus.DUPLICATE;
        }
        if (maxTimestamp != Long.MIN_VALUE && ts < maxTimestamp - allowedLatenessMillis) {
            return IngestStatus.TOO_LATE;
        }
        points.put(ts, sample.getValue());
        if (ts > maxTimestamp) {
            maxTimestamp = ts;
        }
        return IngestStatus.ACCEPTED;
    }

    /**
     * 返回 [fromInclusive, toExclusive) 内的样本快照，按时间戳升序。
     */
    public ArrayList<Map.Entry<Long, Double>> range(long fromInclusive, long toExclusive) {
        return range(fromInclusive, true, toExclusive, false);
    }

    /**
     * 返回指定开闭区间内的样本快照，按时间戳升序。
     */
    public synchronized ArrayList<Map.Entry<Long, Double>> range(long from, boolean fromInclusive,
                                                                  long to, boolean toInclusive) {
        return new ArrayList<>(points.subMap(from, fromInclusive, to, toInclusive).entrySet());
    }

    /** 当前水位线；尚无样本时为 {@link Long#MIN_VALUE}。 */
    public synchronized long getWatermark() {
        return maxTimestamp == Long.MIN_VALUE ? Long.MIN_VALUE : maxTimestamp - allowedLatenessMillis;
    }

    public synchronized long getMaxTimestamp() {
        return maxTimestamp;
    }

    public synchronized int size() {
        return points.size();
    }
}
