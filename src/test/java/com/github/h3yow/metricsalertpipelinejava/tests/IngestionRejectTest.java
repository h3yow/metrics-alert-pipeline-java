package com.github.h3yow.metricsalertpipelinejava.tests;

import com.github.h3yow.metricsalertpipelinejava.config.PipelineProperties;
import com.github.h3yow.metricsalertpipelinejava.model.IngestResponse;
import com.github.h3yow.metricsalertpipelinejava.model.IngestStatus;
import com.github.h3yow.metricsalertpipelinejava.model.MetricSample;
import com.github.h3yow.metricsalertpipelinejava.model.SampleResult;
import com.github.h3yow.metricsalertpipelinejava.service.MetricsService;
import com.github.h3yow.metricsalertpipelinejava.store.InMemoryMetricStore;
import com.github.h3yow.metricsalertpipelinejava.store.MetricSeries;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 接入语义：重复、迟到、时间戳异常必须被明确区分，且不得静默计入。
 */
class IngestionRejectTest {

    private static final Logger log = LoggerFactory.getLogger(IngestionRejectTest.class);

    private final PipelineProperties properties = new PipelineProperties();
    private final TestContext ctx = TestContext.create(properties);
    private final MetricsService service = ctx.service;
    private final InMemoryMetricStore store = ctx.store;

    @Test
    void duplicate_late_futureAndInvalid_areDistinguished() {
        long t = 1_000_000L; // 固定事件时间基准，避免墙钟依赖
        long allowed = properties.getAllowedLatenessMillis();
        long futureLimit = properties.getMaxFutureMillis();

        List<MetricSample> batch = List.of(
                new MetricSample("cpu", t, 10),          // 正常
                new MetricSample("cpu", t, 10),          // 重复：同一时间戳
                new MetricSample("cpu", t + 60_000, 11), // 推进最大时间戳
                new MetricSample("cpu", t + 60_000 - allowed - 1, 12), // 迟到：低于水位线
                new MetricSample("cpu", System.currentTimeMillis() + futureLimit + 60_000, 13), // 未来时间戳
                new MetricSample("cpu", 0, 14),          // 非法时间戳
                new MetricSample("", t, 15)              // 空指标名
        );

        log.info("[接入判定] 输入样本(基准t={}, 允许迟到={}ms): {}", t, allowed, describe(batch));
        IngestResponse resp = service.ingest(batch);
        log.info("[接入判定] 逐条结果: {}", describeResults(resp.getResults()));

        assertEquals(2, resp.getAcceptedCount(), "只有正常样本与推进时间戳的样本被接受");
        assertEquals(5, resp.getRejectedCount());

        MetricSeries series = store.getSeries("cpu");
        assertNotNull(series);
        long expectedWatermark = t + 60_000 - allowed;
        log.info("[窗口判定依据] maxTimestamp={}, watermark=maxTimestamp-allowedLateness={}",
                series.getMaxTimestamp(), series.getWatermark());
        assertEquals(t + 60_000, series.getMaxTimestamp());
        assertEquals(expectedWatermark, series.getWatermark());
        assertEquals(2, series.size(), "被拒绝样本不得计入存储");

        List<IngestStatus> statuses = resp.getResults().stream().map(SampleResult::getStatus).toList();
        assertEquals(List.of(
                IngestStatus.ACCEPTED,
                IngestStatus.DUPLICATE,
                IngestStatus.ACCEPTED,
                IngestStatus.TOO_LATE,
                IngestStatus.FUTURE_TIMESTAMP,
                IngestStatus.INVALID_TIMESTAMP,
                IngestStatus.INVALID_TIMESTAMP), statuses);

        SampleResult late = resp.getResults().get(3);
        assertTrue(late.getReason().contains("watermark"), "迟到原因必须包含水位线判定依据: " + late.getReason());
        log.info("[最终状态] seriesSize={}, watermark={}, 拒绝原因两两可区分: DUPLICATE/TOO_LATE/FUTURE_TIMESTAMP/INVALID_TIMESTAMP",
                series.size(), series.getWatermark());
    }

    static String describe(List<MetricSample> batch) {
        StringBuilder sb = new StringBuilder("[");
        for (MetricSample s : batch) {
            sb.append("{metric=").append(s.getMetric())
                    .append(", ts=").append(s.getTimestamp())
                    .append(", value=").append(s.getValue()).append("}, ");
        }
        return sb.append("]").toString();
    }

    static String describeResults(List<SampleResult> results) {
        StringBuilder sb = new StringBuilder("\n");
        for (SampleResult r : results) {
            sb.append("  - metric=").append(r.getMetric())
                    .append(", ts=").append(r.getTimestamp())
                    .append(", status=").append(r.getStatus())
                    .append(", reason=").append(r.getReason()).append('\n');
        }
        return sb.toString();
    }
}
