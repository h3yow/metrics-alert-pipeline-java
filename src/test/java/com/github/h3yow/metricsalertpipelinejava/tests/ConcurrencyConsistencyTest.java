package com.github.h3yow.metricsalertpipelinejava.tests;

import com.github.h3yow.metricsalertpipelinejava.config.PipelineProperties;
import com.github.h3yow.metricsalertpipelinejava.model.AggregationWindow;
import com.github.h3yow.metricsalertpipelinejava.model.AlertOperator;
import com.github.h3yow.metricsalertpipelinejava.model.AlertRuleConfig;
import com.github.h3yow.metricsalertpipelinejava.model.AlertState;
import com.github.h3yow.metricsalertpipelinejava.model.AlertStatusView;
import com.github.h3yow.metricsalertpipelinejava.model.IngestResponse;
import com.github.h3yow.metricsalertpipelinejava.model.IngestStatus;
import com.github.h3yow.metricsalertpipelinejava.model.MetricSample;
import com.github.h3yow.metricsalertpipelinejava.model.SampleResult;
import com.github.h3yow.metricsalertpipelinejava.service.MetricsService;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发写入 + 乱序到达：
 * 同一批时间戳唯一的样本由多线程乱序写入，最终聚合结果必须与时间戳集合一致，
 * 重复提交产生的 ACCEPTED/DUPLICATE 计数必须稳定，告警最终状态必须确定。
 */
class ConcurrencyConsistencyTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrencyConsistencyTest.class);

    private final PipelineProperties properties = new PipelineProperties();

    private AlertRuleConfig rule() {
        AlertRuleConfig c = new AlertRuleConfig();
        c.setRuleId("c1");
        c.setMetric("latency");
        c.setOperator(AlertOperator.GT);
        c.setThreshold(50d);
        c.setWindowMillis(200_000L); // 大窗口，覆盖全部样本
        c.setMinSamples(50L);
        c.setBreachThreshold(1);
        c.setRecoverThreshold(1);
        return c;
    }

    @Test
    void concurrentOutOfOrderWrites_convergeToConsistentState() throws InterruptedException {
        // 允许迟到设置得很大，保证所有乱序样本都会被接受（并发一致性与迟到语义分别验证）
        properties.setAllowedLatenessMillis(10_000_000L);
        TestContext ctx = TestContext.create(properties);
        MetricsService service = ctx.service;
        service.registerRule(rule());

        long base = 10_000_000L;
        int uniqueCount = 200;
        List<MetricSample> uniqueSamples = new ArrayList<>();
        for (int i = 0; i < uniqueCount; i++) {
            // 时间戳唯一且稀疏（间隔 100ms），值与时间戳一一对应，全部 >50 => 最终必然 FIRING
            uniqueSamples.add(new MetricSample("latency", base + i * 100L, 100 + (i % 5)));
        }

        int threads = 8;
        int rounds = 3; // 每轮把同一批样本全部提交一次 => 第1轮全接受，后2轮全重复
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ConcurrentLinkedQueue<IngestResponse> responses = new ConcurrentLinkedQueue<>();

        List<List<MetricSample>> shuffledCopies = new ArrayList<>();
        for (int r = 0; r < rounds; r++) {
            List<MetricSample> copy = new ArrayList<>(uniqueSamples);
            Collections.shuffle(copy);
            shuffledCopies.add(copy);
        }
        // 把 rounds * uniqueSamples 个写入任务切成 threads 份（按样本任务粒度切分）
        List<MetricSample> allWrites = new ArrayList<>();
        for (List<MetricSample> copy : shuffledCopies) {
            allWrites.addAll(copy);
        }
        Collections.shuffle(allWrites);
        int chunkSize = (allWrites.size() + threads - 1) / threads;

        log.info("[并发输入] threads={}, 唯一时间戳={}, 总写入任务(含重复提交)={}, 单批={}",
                threads, uniqueCount, allWrites.size(), chunkSize);

        for (int t = 0; t < threads; t++) {
            int from = t * chunkSize;
            int to = Math.min(allWrites.size(), from + chunkSize);
            List<MetricSample> mine = new ArrayList<>(allWrites.subList(from, to));
            pool.submit(() -> {
                try {
                    start.await();
                    // 再在线程内乱序
                    Collections.shuffle(mine);
                    for (int i = 0; i < mine.size(); i += 50) {
                        List<MetricSample> batch = mine.subList(i, Math.min(mine.size(), i + 50));
                        responses.add(service.ingest(new ArrayList<>(batch)));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "并发写入必须在超时前完成");
        pool.shutdown();

        int accepted = 0;
        int duplicate = 0;
        int other = 0;
        for (IngestResponse r : responses) {
            for (SampleResult sr : r.getResults()) {
                if (sr.getStatus() == IngestStatus.ACCEPTED) {
                    accepted++;
                } else if (sr.getStatus() == IngestStatus.DUPLICATE) {
                    duplicate++;
                } else {
                    other++;
                }
            }
        }
        log.info("[接入计数] ACCEPTED={}, DUPLICATE={}, 其它拒绝={}", accepted, duplicate, other);
        assertEquals(uniqueCount, accepted, "无论如何并发，唯一时间戳恰好接受一次");
        assertEquals(uniqueCount * (rounds - 1), duplicate, "重复提交必须全部判为 DUPLICATE");
        assertEquals(0, other, "大迟到窗口下不应出现迟到/未来/非法拒绝");

        long from = base;
        long to = base + uniqueCount * 100L;
        List<AggregationWindow> windows = service.query("latency", from, to, to - from);
        AggregationWindow all = windows.get(0);
        double expectedSum = uniqueSamples.stream().collect(Collectors.summingDouble(MetricSample::getValue));
        log.info("[窗口判定依据] 单窗口 [{},{}) 期望 count={} sum={}，实际 count={} sum={}",
                from, to, uniqueCount, expectedSum, all.getCount(), all.getSum());
        assertEquals(uniqueCount, all.getCount());
        assertEquals(expectedSum, all.getSum(), 1e-6, "乱序并发写入后的聚合必须与时间戳集合一致");

        AlertStatusView status = service.alertStatus("c1");
        log.info("[最终告警状态] state={}, lastCount={}, 迁移链长度={}",
                status.getState(), status.getLastSampleCount(), status.getTransitions().size());
        assertEquals(AlertState.FIRING, status.getState(), "全部样本均越限，最终状态必须确定为 FIRING");
        status.getTransitions().forEach(tr -> log.info("    {} -> {} t={} avg={} count={}: {}",
                tr.getFrom(), tr.getTo(), tr.getEventTime(),
                String.format("%.1f", tr.getWindowValue()), tr.getSampleCount(), tr.getReason()));
    }
}
