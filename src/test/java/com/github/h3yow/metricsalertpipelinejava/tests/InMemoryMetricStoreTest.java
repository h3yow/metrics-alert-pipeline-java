package com.github.h3yow.metricsalertpipelinejava.tests;

import com.github.h3yow.metricsalertpipelinejava.common.ApiException;
import com.github.h3yow.metricsalertpipelinejava.common.ErrorCode;
import com.github.h3yow.metricsalertpipelinejava.model.AggregationResult;
import com.github.h3yow.metricsalertpipelinejava.model.IngestResponse;
import com.github.h3yow.metricsalertpipelinejava.model.IngestStatus;
import com.github.h3yow.metricsalertpipelinejava.model.Sample;
import com.github.h3yow.metricsalertpipelinejava.model.SampleResult;
import com.github.h3yow.metricsalertpipelinejava.service.InMemoryMetricStore;
import com.github.h3yow.metricsalertpipelinejava.tests.support.ManualClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 存储层单测。每个用例都打印输入样本、窗口/水位线判定依据与最终聚合状态。
 */
class InMemoryMetricStoreTest {

    private static final Logger log = LoggerFactory.getLogger(InMemoryMetricStoreTest.class);

    private static final long T0 = 1_000_000L;

    private ManualClock clock;
    private InMemoryMetricStore store;

    @BeforeEach
    void setUp() {
        clock = new ManualClock(T0);
        store = new InMemoryMetricStore(clock);
        store.registerMetric("cpu");
    }

    private void logSamples(String label, List<Sample> samples) {
        log.info("[{}] input samples: {}", label, samples.stream()
                .map(s -> s == null ? "null" : "(" + s.metric() + "@" + s.timestamp() + "=" + s.value() + ")")
                .collect(Collectors.joining(", ")));
    }

    @Test
    @DisplayName("乱序到达：先接受的较晚样本推高水位线，迟到样本拒绝，在窗口内的乱序样本仍计入")
    void outOfOrderAndLateness() {
        // 模拟实时接入：处理时钟推进到 T0+100s。
        // 先写入 ts=T0+100s 的样本，事件时间水位线变为 T0+40s
        clock.setNow(T0 + 100_000);
        List<Sample> first = List.of(sample("cpu", T0 + 100_000, 10));
        logSamples("late-first-batch", first);
        IngestResponse r1 = store.ingest(first);
        assertEquals(IngestStatus.ACCEPTED, r1.results().get(0).status());
        log.info("watermark after first batch: {}", T0 + 100_000 - InMemoryMetricStore.MAX_LATENESS_MILLIS);

        // 第二批：窗口内乱序（接受）、低于水位线（迟到拒绝）、重复（忽略）、冲突值（拒绝）
        List<Sample> second = List.of(
                sample("cpu", T0 + 50_000, 5),       // 高于水位线 T0+40s，乱序但接受
                sample("cpu", T0 + 10_000, 1),       // 低于水位线，迟到拒绝
                sample("cpu", T0 + 100_000, 10),     // 完全重复，忽略
                sample("cpu", T0 + 100_000, 99));    // 同 ts 不同值，拒绝
        logSamples("late-second-batch", second);
        IngestResponse r2 = store.ingest(second);

        Map<IngestStatus, Long> grouped = r2.results().stream()
                .collect(Collectors.groupingBy(SampleResult::status, Collectors.counting()));
        log.info("second batch decisions: {}", grouped);
        r2.results().forEach(res -> log.info("  decision ts={} value={} -> {} reason={}",
                res.sample().timestamp(), res.sample().value(), res.status(), res.reason()));

        assertEquals(1, r2.accepted());
        assertEquals(1, r2.ignored());
        assertEquals(2, r2.rejected());
        assertEquals(IngestStatus.ACCEPTED, r2.results().get(0).status());
        assertEquals(IngestStatus.LATE_REJECTED, r2.results().get(1).status());
        assertEquals(IngestStatus.DUPLICATE_IGNORED, r2.results().get(2).status());
        assertEquals(IngestStatus.CONFLICTING_VALUE, r2.results().get(3).status());

        AggregationResult agg = store.aggregate("cpu", T0, T0 + 200_000);
        log.info("final aggregation [{},{}): count={} avg={} min={} max={} sum={}",
                agg.windowStart(), agg.windowEnd(), agg.count(), agg.average(),
                agg.min(), agg.max(), agg.sum());
        assertEquals(2L, agg.count(), "迟到/重复/冲突样本均不得计入");
        assertEquals(7.5d, agg.average(), 1e-9);
    }

    @Test
    @DisplayName("时间戳异常：非正、未来时间戳、未登记指标均被区分拒绝")
    void invalidTimestampsAndUnknownMetric() {
        clock.setNow(T0 + 100_000);
        List<Sample> bad = new ArrayList<>(List.of(
                sample("cpu", 0L, 1),
                sample("cpu", -5L, 1),
                sample("cpu", clock.nowMillis() + InMemoryMetricStore.MAX_FUTURE_MILLIS + 1, 1),
                sample("unknown-metric", T0 + 100, 1)));
        bad.add(null);
        logSamples("invalid-batch", bad);
        IngestResponse resp = store.ingest(bad);
        resp.results().forEach(res ->
                log.info("  decision -> {} sample={} reason={}", res.status(),
                        res.sample() == null ? "null"
                                : ("ts=" + res.sample().timestamp() + " value=" + res.sample().value()),
                        res.reason()));
        assertEquals(0, resp.accepted());
        assertEquals(IngestStatus.INVALID_TIMESTAMP, resp.results().get(0).status());
        assertEquals(IngestStatus.INVALID_TIMESTAMP, resp.results().get(1).status());
        assertEquals(IngestStatus.FUTURE_TIMESTAMP, resp.results().get(2).status());
        assertEquals(IngestStatus.METRIC_UNREGISTERED, resp.results().get(3).status());
        assertEquals(IngestStatus.INVALID_SAMPLE, resp.results().get(4).status());
    }

    @Test
    @DisplayName("左闭右开窗口聚合：边界样本归属正确，乱序数据不影响结果")
    void windowBoundsAndOrderIndependence() {
        clock.setNow(T0 + 60_000);
        List<Sample> samples = List.of(
                sample("cpu", T0 + 10_000, 10),
                sample("cpu", T0 + 30_000, 30), // 故意先发晚的
                sample("cpu", T0 + 20_000, 20),
                sample("cpu", T0 + 60_000, 60)); // 恰好等于右端点，排除
        logSamples("window-bounds", samples);
        store.ingest(samples);

        AggregationResult agg = store.aggregate("cpu", T0 + 10_000, T0 + 60_000);
        log.info("window [{},{}) decision: left-inclusive right-exclusive, count={} values=[10,20,30]",
                agg.windowStart(), agg.windowEnd(), agg.count());
        assertEquals(3L, agg.count());
        assertEquals(20d, agg.average(), 1e-9);
        assertEquals(10d, agg.min(), 0d);
        assertEquals(30d, agg.max(), 0d);

        // 空窗口返回 count=0，而不是报错
        AggregationResult empty = store.aggregate("cpu", T0 + 100_000, T0 + 200_000);
        assertEquals(0L, empty.count());
    }

    @Test
    @DisplayName("并发批量写入：每个时间戳仅被一个线程接受，最终计数与聚合确定")
    void concurrentWritesAreConsistent() throws Exception {
        final int threads = 8;
        final int n = 200;
        clock.setNow(T0 + 2_000_000L);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger acceptedTotal = new AtomicInteger();
        AtomicInteger duplicateTotal = new AtomicInteger();
        List<Callable<Void>> tasks = new ArrayList<>();

        for (int t = 0; t < threads; t++) {
            final int seed = t;
            // 所有线程写入同一批时间戳，触发重复；并各写一个线程专属时间戳
            tasks.add(() -> {
                List<Sample> batch = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    batch.add(sample("cpu", T0 + (i + 1) * 1000L, i));
                }
                batch.add(sample("cpu", T0 + 1_000_000L + seed * 1000L, seed));
                logSamples("concurrent-thread-" + seed + " size=" + batch.size(), batch.subList(0, 3));
                IngestResponse resp = store.ingest(batch);
                acceptedTotal.addAndGet(resp.accepted());
                duplicateTotal.addAndGet(resp.ignored());
                return null;
            });
        }
        List<Future<Void>> futures = pool.invokeAll(tasks);
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        for (Future<Void> f : futures) {
            f.get();
        }

        AggregationResult agg = store.aggregate("cpu", T0, T0 + 2_000_000L);
        log.info("concurrent final state: accepted={} duplicates={} count={} sum={}",
                acceptedTotal.get(), duplicateTotal.get(), agg.count(), agg.sum());
        // 200 个共享时间戳各只接受一次 + 8 个专属时间戳
        assertEquals(208, acceptedTotal.get());
        assertEquals(200 * (threads - 1), duplicateTotal.get());
        assertEquals(208L, agg.count());
        // 共享时间戳值不依赖写入线程：i 从 0..199，恰好接受一次
        double expectedSum = (0 + 199) * 200L / 2d + (0 + 7) * 8L / 2d;
        assertEquals(expectedSum, agg.sum(), 1e-6);
    }

    @Test
    @DisplayName("读取接口：未知指标/非法窗口/越界查询返回彼此可区分的错误码")
    void readApiValidation() {
        ApiException unknown = assertThrows(ApiException.class,
                () -> store.aggregate("nope", T0, T0 + 1000));
        log.info("aggregate unknown metric -> {}: {}", unknown.getCode(), unknown.getMessage());
        assertEquals(ErrorCode.METRIC_NOT_FOUND, unknown.getCode());

        ApiException badWindow = assertThrows(ApiException.class,
                () -> store.aggregate("cpu", T0 + 100, T0));
        assertEquals(ErrorCode.INVALID_WINDOW, badWindow.getCode());

        ApiException badRange = assertThrows(ApiException.class,
                () -> store.queryRange("cpu", T0 + 100, T0, 1000));
        assertEquals(ErrorCode.INVALID_QUERY_RANGE, badRange.getCode());

        ApiException badBucket = assertThrows(ApiException.class,
                () -> store.queryRange("cpu", T0, T0 + 1000, 0));
        assertEquals(ErrorCode.INVALID_WINDOW, badBucket.getCode());

        ApiException notDivisible = assertThrows(ApiException.class,
                () -> store.queryRange("cpu", T0, T0 + 1000, 300));
        assertEquals(ErrorCode.INVALID_WINDOW, notDivisible.getCode());

        ApiException tooManyBuckets = assertThrows(ApiException.class,
                () -> store.queryRange("cpu", T0, T0 + 200_000, 100));
        assertEquals(ErrorCode.INVALID_QUERY_RANGE, tooManyBuckets.getCode());
    }

    @Test
    @DisplayName("区间查询：按桶对齐，桶内聚合与最终状态可解释")
    void queryRangeBucketing() {
        List<Sample> samples = List.of(
                sample("cpu", T0 + 0, 2),
                sample("cpu", T0 + 999, 4),
                sample("cpu", T0 + 1000, 8),
                sample("cpu", T0 + 1999, 10));
        logSamples("range-query", samples);
        store.ingest(samples);

        List<AggregationResult> results =
                store.queryRange("cpu", T0, T0 + 2000, 1000);
        results.forEach(a -> log.info("bucket [{},{}): count={} avg={}",
                a.windowStart(), a.windowEnd(), a.count(), a.average()));
        assertEquals(2, results.size());
        assertEquals(3d, results.get(0).average(), 1e-9);
        assertEquals(9d, results.get(1).average(), 1e-9);
    }

    private Sample sample(String metric, long ts, double value) {
        return new Sample(metric, ts, value);
    }
}
