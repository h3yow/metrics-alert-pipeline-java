package com.github.h3yow.metricsalertpipelinejava.api;

import com.github.h3yow.metricsalertpipelinejava.model.AggregationWindow;
import com.github.h3yow.metricsalertpipelinejava.model.AlertRuleConfig;
import com.github.h3yow.metricsalertpipelinejava.model.AlertStatusView;
import com.github.h3yow.metricsalertpipelinejava.model.IngestResponse;
import com.github.h3yow.metricsalertpipelinejava.model.MetricSample;
import com.github.h3yow.metricsalertpipelinejava.service.MetricsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class MetricsController {

    private final MetricsService service;

    public MetricsController(MetricsService service) {
        this.service = service;
    }

    @PostMapping("/metrics/ingest")
    public IngestResponse ingest(@RequestBody List<MetricSample> samples) {
        return service.ingest(samples);
    }

    @GetMapping("/metrics/{metric}/windows")
    public List<AggregationWindow> windows(@PathVariable String metric,
                                           @RequestParam("from") long from,
                                           @RequestParam("to") long to,
                                           @RequestParam("windowMillis") long windowMillis) {
        return service.query(metric, from, to, windowMillis);
    }

    @PostMapping("/alerts/rules")
    public AlertStatusView registerRule(@RequestBody AlertRuleConfig config) {
        return service.registerRule(config);
    }

    @GetMapping("/alerts/rules/{ruleId}")
    public AlertStatusView alertStatus(@PathVariable String ruleId) {
        return service.alertStatus(ruleId);
    }

    @GetMapping("/alerts/rules")
    public List<AlertStatusView> listRules(@RequestParam(value = "metric", required = false) String metric) {
        return metric == null ? service.allAlertStatus() : service.alertStatusByMetric(metric);
    }
}
