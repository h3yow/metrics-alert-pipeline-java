package com.github.h3yow.metricsalertpipelinejava.engine;

import com.github.h3yow.metricsalertpipelinejava.model.AggregationWindow;
import com.github.h3yow.metricsalertpipelinejava.store.InMemoryMetricStore;
import com.github.h3yow.metricsalertpipelinejava.store.MetricSeries;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 聚合引擎：全部基于事件时间（样本自带时间戳）计算，结果与写入顺序无关，
 * 因此乱序到达后重算仍得到相同聚合结果。
 *
 * <ul>
 *   <li>查询用滚动窗口：按 epoch 对齐，窗口为 [start, start+windowMillis)；</li>
 *   <li>告警判定用滑动窗口：在事件时间 t 处取 (t-windowMillis, t]（左开右闭）。</li>
 * </ul>
 */
@Component
public class AggregationEngine {

    /** 单次查询允许返回的最大窗口数量，防止非法超大范围扫描内存。 */
    public static final int MAX_WINDOWS_PER_QUERY = 10_000;

    private final InMemoryMetricStore store;

    public AggregationEngine(InMemoryMetricStore store) {
        this.store = store;
    }

    /**
     * 滚动窗口查询。调用方需保证参数已校验、指标存在。
     * 返回所有与 [fromEpochMillis, toEpochMillis) 相交的窗口，含空窗口（count=0），
     * 窗口按 epoch 对齐：start = floor(ts / windowMillis) * windowMillis。
     */
    public List<AggregationWindow> tumblingQuery(String metric, long fromEpochMillis, long toEpochMillis,
                                                 long windowMillis) {
        MetricSeries series = store.getSeries(metric);
        List<AggregationWindow> out = new ArrayList<>();
        long start = Math.floorDiv(fromEpochMillis, windowMillis) * windowMillis;
        for (long wStart = start; wStart < toEpochMillis; wStart += windowMillis) {
            long wEnd = wStart + windowMillis;
            out.add(aggregate(series, wStart, wEnd));
        }
        return out;
    }

    /**
     * 事件时间 eventTime 处的滑动窗口聚合，区间为 (eventTime-windowMillis, eventTime]。
     */
    public AggregationWindow slidingAt(String metric, long eventTime, long windowMillis) {
        MetricSeries series = store.getSeries(metric);
        long from = eventTime - windowMillis;
        long to = eventTime;
        List<Map.Entry<Long, Double>> points = series == null ? List.of() : series.range(from, false, to, true);
        return buildWindow(from, to, points);
    }

    private AggregationWindow aggregate(MetricSeries series, long wStart, long wEnd) {
        List<Map.Entry<Long, Double>> points =
                series == null ? List.of() : series.range(wStart, true, wEnd, false);
        return buildWindow(wStart, wEnd, points);
    }

    private AggregationWindow buildWindow(long wStart, long wEnd, List<Map.Entry<Long, Double>> points) {
        if (points.isEmpty()) {
            return new AggregationWindow(wStart, wEnd, 0, 0d, 0d, 0d, 0d);
        }
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        double sum = 0d;
        for (Map.Entry<Long, Double> p : points) {
            double v = p.getValue();
            if (v < min) {
                min = v;
            }
            if (v > max) {
                max = v;
            }
            sum += v;
        }
        long count = points.size();
        return new AggregationWindow(wStart, wEnd, count, min, max, sum, sum / count);
    }
}
