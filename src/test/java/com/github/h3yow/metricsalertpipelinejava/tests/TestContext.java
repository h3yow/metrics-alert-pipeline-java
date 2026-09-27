package com.github.h3yow.metricsalertpipelinejava.tests;

import com.github.h3yow.metricsalertpipelinejava.config.PipelineProperties;
import com.github.h3yow.metricsalertpipelinejava.engine.AggregationEngine;
import com.github.h3yow.metricsalertpipelinejava.engine.AlertEngine;
import com.github.h3yow.metricsalertpipelinejava.service.MetricsService;
import com.github.h3yow.metricsalertpipelinejava.store.InMemoryMetricStore;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 纯单元测试装配：不启动 Spring 容器，直接构造组件。
 */
final class TestContext {

    final InMemoryMetricStore store;
    final AggregationEngine aggregationEngine;
    final AlertEngine alertEngine;
    final MetricsService service;

    private TestContext(InMemoryMetricStore store, AggregationEngine aggregationEngine,
                        AlertEngine alertEngine, MetricsService service) {
        this.store = store;
        this.aggregationEngine = aggregationEngine;
        this.alertEngine = alertEngine;
        this.service = service;
    }

    static TestContext create(PipelineProperties properties) {
        InMemoryMetricStore store = new InMemoryMetricStore();
        store.setAllowedLatenessMillis(properties.getAllowedLatenessMillis());
        AggregationEngine agg = new AggregationEngine(store);
        ObjectProvider<AggregationEngine> provider = new ObjectProvider<>() {
            @Override
            public AggregationEngine getObject() {
                return agg;
            }

            @Override
            public AggregationEngine getIfAvailable() {
                return agg;
            }
        };
        AlertEngine alerts = new AlertEngine(properties, provider);
        MetricsService service = new MetricsService(store, agg, alerts, properties);
        return new TestContext(store, agg, alerts, service);
    }
}
