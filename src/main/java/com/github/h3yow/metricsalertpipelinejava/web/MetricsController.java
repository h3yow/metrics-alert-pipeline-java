package com.github.h3yow.metricsalertpipelinejava.web;

import com.github.h3yow.metricsalertpipelinejava.common.ApiException;
import com.github.h3yow.metricsalertpipelinejava.common.ErrorCode;
import com.github.h3yow.metricsalertpipelinejava.model.AggregationResult;
import com.github.h3yow.metricsalertpipelinejava.model.IngestResponse;
import com.github.h3yow.metricsalertpipelinejava.service.MetricStore;
import com.github.h3yow.metricsalertpipelinejava.web.dto.IngestRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 指标写入与聚合读取接口。 */
@RestController
@RequestMapping("/api/metrics")
public class MetricsController {

    private final MetricStore metricStore;

    public MetricsController(MetricStore metricStore) {
        this.metricStore = metricStore;
    }

    /** 批量接入样本；每条样本的接受/忽略/拒绝结论互斥可区分。 */
    @PostMapping("/ingest")
    public IngestResponse ingest(@RequestBody(required = false) IngestRequest request) {
        if (request == null || request.samples() == null) {
            throw new ApiException(ErrorCode.INVALID_SAMPLE, "samples must not be null");
        }
        return metricStore.ingest(request.samples());
    }

    /** 登记指标，重复登记幂等。 */
    @PostMapping("/register")
    public void register(@RequestParam String metric) {
        metricStore.registerMetric(metric);
    }

    /** 指定 [windowStart, windowEnd) 左闭右开窗口聚合。 */
    @PostMapping("/aggregate")
    public AggregationResult aggregate(@RequestParam String metric,
                                       @RequestParam long windowStart,
                                       @RequestParam long windowEnd) {
        return metricStore.aggregate(metric, windowStart, windowEnd);
    }

    /** 按固定桶宽读取 [from, to) 区间的聚合序列。 */
    @PostMapping("/query")
    public List<AggregationResult> queryRange(@RequestParam String metric,
                                              @RequestParam long from,
                                              @RequestParam long to,
                                              @RequestParam long bucketMillis) {
        return metricStore.queryRange(metric, from, to, bucketMillis);
    }
}
