package com.github.h3yow.metricsalertpipelinejava.tests;

import com.github.h3yow.metricsalertpipelinejava.config.PipelineProperties;
import com.github.h3yow.metricsalertpipelinejava.exception.ApiErrorCode;
import com.github.h3yow.metricsalertpipelinejava.exception.ApiException;
import com.github.h3yow.metricsalertpipelinejava.model.AggregationWindow;
import com.github.h3yow.metricsalertpipelinejava.model.MetricSample;
import com.github.h3yow.metricsalertpipelinejava.service.MetricsService;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 聚合语义：基于事件时间的滚动窗口，乱序写入与顺序写入结果一致；
 * 查询的各类非法输入返回彼此可区分的错误码。
 */
class AggregationQueryTest {

    private static final Logger log = LoggerFactory.getLogger(AggregationQueryTest.class);

    private final PipelineProperties properties = new PipelineProperties();
    private final TestContext ctx = TestContext.create(properties);
    private final MetricsService service = ctx.service;

    @Test
    void outOfOrderWritesProduceSameAggregation() {
        long base = 2_000_000L;
        List<MetricSample> ordered = new ArrayList<>();
        // 窗口 [0,1000): ts=0 ->10, ts=500 ->20 ; 窗口 [1000,2000): ts=1000 ->30
        ordered.add(new MetricSample("mem", base, 10));
        ordered.add(new MetricSample("mem", base + 500, 20));
        ordered.add(new MetricSample("mem", base + 1000, 30));

        List<MetricSample> shuffled = new ArrayList<>(ordered);
        Collections.shuffle(shuffled);
        log.info("[聚合乱序] 原始顺序 ts={}, 打乱后顺序 ts={}",
                ordered.stream().map(MetricSample::getTimestamp).toList(),
                shuffled.stream().map(MetricSample::getTimestamp).toList());
        service.ingest(shuffled);

        List<AggregationWindow> windows = service.query("mem", base, base + 2000L, 1000L);
        log.info("[窗口判定依据] windowMillis=1000, 对齐 start=floor(ts/1000)*1000, 区间=[start,end)");
        windows.forEach(w -> log.info("[聚合结果] [{},{}) count={} min={} max={} avg={}",
                w.getWindowStart(), w.getWindowEnd(), w.getCount(), w.getMin(), w.getMax(), w.getAvg()));

        assertEquals(2, windows.size());
        AggregationWindow w0 = windows.get(0);
        assertEquals(base, w0.getWindowStart());
        assertEquals(2, w0.getCount());
        assertEquals(10, w0.getMin());
        assertEquals(20, w0.getMax());
        assertEquals(15, w0.getAvg(), 1e-9);
        AggregationWindow w1 = windows.get(1);
        assertEquals(base + 1000, w1.getWindowStart());
        assertEquals(1, w1.getCount());
        assertEquals(30, w1.getAvg(), 1e-9);
        log.info("[最终状态] 乱序写入后的聚合与顺序写入完全一致（窗口边界与统计量相同）");
    }

    @Test
    void lateSampleWithinLatenessIsCounted_andLateBeyondIsNot() {
        long base = 3_000_000L;
        long allowed = properties.getAllowedLatenessMillis();
        service.ingest(List.of(
                new MetricSample("net", base + 60_000, 9),      // 先推进事件时间
                new MetricSample("net", base + 60_000 - allowed + 100, 5) // 允许迟到范围内 -> 计入
        ));
        var resp = service.ingest(List.of(
                new MetricSample("net", base + 60_000 - allowed - 1, 5))); // 超过允许迟到 -> TOO_LATE
        assertEquals(com.github.h3yow.metricsalertpipelinejava.model.IngestStatus.TOO_LATE,
                resp.getResults().get(0).getStatus());

        List<AggregationWindow> windows = service.query("net", base, base + 120_000L, 120_000L);
        AggregationWindow w = windows.get(0);
        log.info("[迟到窗口] 窗口内 count={}（含允许迟到内的样本，排除超窗样本）", w.getCount());
        assertEquals(2, w.getCount());
    }

    @Test
    void failureReasonsAreDistinguishable() {
        service.ingest(List.of(new MetricSample("disk", 4_000_000L, 1)));

        assertCode(ApiErrorCode.UNKNOWN_METRIC,
                () -> service.query("ghost", 4_000_000L, 5_000_000L, 1000L));
        assertCode(ApiErrorCode.ILLEGAL_WINDOW,
                () -> service.query("disk", 4_000_000L, 5_000_000L, 0L));
        assertCode(ApiErrorCode.OUT_OF_RANGE,
                () -> service.query("disk", 5_000_000L, 4_000_000L, 1000L));
        assertCode(ApiErrorCode.ILLEGAL_WINDOW,
                () -> service.query("disk", 4_000_000L, 4_000_000L + 20_000_000L, 1L));
        log.info("[查询拒绝] UNKNOWN_METRIC / ILLEGAL_WINDOW / OUT_OF_RANGE 均可区分地抛出");
    }

    private void assertCode(ApiErrorCode expected, Runnable runnable) {
        ApiException ex = assertThrows(ApiException.class, runnable::run);
        assertEquals(expected, ex.getCode(), "unexpected code for message: " + ex.getMessage());
    }
}
