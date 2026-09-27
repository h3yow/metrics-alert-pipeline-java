package com.github.h3yow.metricsalertpipelinejava.store;

import com.github.h3yow.metricsalertpipelinejava.model.IngestStatus;
import com.github.h3yow.metricsalertpipelinejava.model.MetricSample;
import com.github.h3yow.metricsalertpipelinejava.model.SampleResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存时序存储入口：按指标名分桶，每个 {@link MetricSeries} 自带锁，
 * 因此不同指标的并发写入互不阻塞；同一指标的写入串行化，保证乱序到达时
 * 水位线、去重与最终数据集合一致。
 */
@Component
public class InMemoryMetricStore {

    private final ConcurrentHashMap<String, MetricSeries> seriesByName = new ConcurrentHashMap<>();
    private volatile long allowedLatenessMillis = 5000L;

    public InMemoryMetricStore() {
    }

    public void setAllowedLatenessMillis(long allowedLatenessMillis) {
        this.allowedLatenessMillis = allowedLatenessMillis;
    }

    /**
     * 接入单条样本，返回明确的判定结果与原因。
     * 校验顺序固定，保证拒绝原因可区分、可复现：
     * 空指标名/非法时间戳 -&gt; 未来时间戳 -&gt; 重复 -&gt; 迟到。
     */
    public SampleResult ingest(MetricSample sample, long nowMillis, long allowedLatenessMillis, long maxFutureMillis) {
        String metric = sample.getMetric();
        long ts = sample.getTimestamp();
        if (metric == null || metric.isBlank()) {
            return new SampleResult(metric, ts, IngestStatus.INVALID_TIMESTAMP,
                    "metric name must not be blank");
        }
        if (ts <= 0) {
            return new SampleResult(metric, ts, IngestStatus.INVALID_TIMESTAMP,
                    "timestamp must be a positive epoch-milli value, got " + ts);
        }
        if (ts > nowMillis + maxFutureMillis) {
            return new SampleResult(metric, ts, IngestStatus.FUTURE_TIMESTAMP,
                    "timestamp " + ts + " is more than " + maxFutureMillis
                            + "ms ahead of now " + nowMillis);
        }
        // computeIfAbsent 保证同名序列只创建一次；后续判定在序列锁内完成。
        MetricSeries series = seriesByName.computeIfAbsent(
                metric, m -> new MetricSeries(m, allowedLatenessMillis));
        IngestStatus status = series.insert(sample);
        String reason = switch (status) {
            case ACCEPTED -> "accepted into event-time series";
            case DUPLICATE -> "sample with timestamp " + ts + " already exists for metric '" + metric + "'";
            case TOO_LATE -> "timestamp " + ts + " is behind watermark " + series.getWatermark()
                    + " (maxTimestamp=" + series.getMaxTimestamp()
                    + ", allowedLateness=" + allowedLatenessMillis + "ms)";
            default -> "rejected";
        };
        return new SampleResult(metric, ts, status, reason);
    }

    /** 只读获取已有序列，未知指标返回 null。 */
    public MetricSeries getSeries(String metric) {
        return seriesByName.get(metric);
    }

    public boolean hasSeries(String metric) {
        return seriesByName.containsKey(metric);
    }
}
