package com.github.h3yow.metricsalertpipelinejava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 端到端：验证失败原因在 HTTP 层面彼此可区分，且只有 error/message/timestamp 三段，
 * 不回传内部异常类名或堆栈。
 */
@SpringBootTest
@AutoConfigureMockMvc
class WebApiIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mvc;

    private JsonNode errorBody(String response) throws Exception {
        return MAPPER.readTree(response);
    }

    @Test
    void unknownMetricVsUnknownRuleHaveDistinctCodes() throws Exception {
        // 未知指标聚合 -> 404 METRIC_NOT_FOUND
        String metricBody = mvc.perform(post("/api/metrics/aggregate")
                        .param("metric", "nope")
                        .param("windowStart", "1000")
                        .param("windowEnd", "2000"))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        JsonNode metricErr = errorBody(metricBody);
        assertEquals("METRIC_NOT_FOUND", metricErr.path("error").asText());

        // 未知规则状态 -> 404 RULE_NOT_FOUND
        String ruleBody = mvc.perform(get("/api/alerts/rules/nope/status"))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        JsonNode ruleErr = errorBody(ruleBody);
        assertEquals("RULE_NOT_FOUND", ruleErr.path("error").asText());
        assertNotEquals(metricErr.path("error").asText(), ruleErr.path("error").asText());
    }

    @Test
    void illegalWindowAndRangeAreDistinct() throws Exception {
        mvc.perform(post("/api/metrics/register").param("metric", "m1")).andExpect(status().isOk());

        String windowBody = mvc.perform(post("/api/metrics/aggregate")
                        .param("metric", "m1")
                        .param("windowStart", "2000")
                        .param("windowEnd", "1000"))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();
        assertEquals("INVALID_WINDOW", errorBody(windowBody).path("error").asText());

        String rangeBody = mvc.perform(post("/api/metrics/query")
                        .param("metric", "m1")
                        .param("from", "2000")
                        .param("to", "1000")
                        .param("bucketMillis", "100"))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();
        assertEquals("INVALID_QUERY_RANGE", errorBody(rangeBody).path("error").asText());
    }

    @Test
    void ingestEndToEndClassifiesSamples() throws Exception {
        mvc.perform(post("/api/metrics/register").param("metric", "net")).andExpect(status().isOk());
        long now = System.currentTimeMillis();

        String payload = MAPPER.writeValueAsString(java.util.Map.of(
                "samples", java.util.List.of(
                        java.util.Map.of("metric", "net", "timestamp", now - 1000, "value", 1),
                        java.util.Map.of("metric", "net", "timestamp", now - 1000, "value", 1),
                        java.util.Map.of("metric", "net", "timestamp", 0, "value", 1),
                        java.util.Map.of("metric", "ghost", "timestamp", now, "value", 1))));

        String body = mvc.perform(post("/api/metrics/ingest")
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode json = errorBody(body);
        assertEquals(1, json.path("accepted").asInt());
        assertEquals(1, json.path("ignored").asInt());
        assertEquals(2, json.path("rejected").asInt());
        assertEquals("DUPLICATE_IGNORED", json.path("results").get(1).path("status").asText());
        assertEquals("INVALID_TIMESTAMP", json.path("results").get(2).path("status").asText());
        assertEquals("METRIC_UNREGISTERED", json.path("results").get(3).path("status").asText());
    }

    @Test
    void malformedBodyIsRejectedWithoutStackLeak() throws Exception {
        String body = mvc.perform(post("/api/metrics/ingest")
                        .contentType(MediaType.APPLICATION_JSON).content("{not-json"))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();
        JsonNode err = errorBody(body);
        assertEquals("INVALID_SAMPLE", err.path("error").asText());
        assertEquals(false, body.contains("Exception"));
    }
}
