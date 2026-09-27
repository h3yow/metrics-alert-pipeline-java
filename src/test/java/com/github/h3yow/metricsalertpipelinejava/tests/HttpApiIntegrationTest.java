package com.github.h3yow.metricsalertpipelinejava.tests;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 边界集成测试：错误以稳定 errorCode 返回，不透出内部异常。
 */
@SpringBootTest
@AutoConfigureMockMvc
class HttpApiIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void endToEnd_ingestQueryAlert_andDistinctErrors() throws Exception {
        long ts = 20_000_000L;

        // 空批次 -> EMPTY_BATCH
        mvc.perform(post("/api/metrics/ingest").contentType(MediaType.APPLICATION_JSON).content("[]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("EMPTY_BATCH"));

        // 畸形 JSON -> INVALID_PAYLOAD（不泄露 Jackson 内部细节）
        mvc.perform(post("/api/metrics/ingest").contentType(MediaType.APPLICATION_JSON).content("{not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PAYLOAD"));

        // 正常接入两条样本
        String body = "[{\"metric\":\"cpu\",\"timestamp\":" + ts + ",\"value\":10},"
                + "{\"metric\":\"cpu\",\"timestamp\":" + (ts + 100) + ",\"value\":20},"
                + "{\"metric\":\"cpu\",\"timestamp\":" + ts + ",\"value\":10}]";
        mvc.perform(post("/api/metrics/ingest").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acceptedCount").value(2))
                .andExpect(jsonPath("$.rejectedCount").value(1))
                .andExpect(jsonPath("$.results[2].status").value("DUPLICATE"));

        // 未知指标 -> UNKNOWN_METRIC
        mvc.perform(get("/api/metrics/ghost/windows")
                        .param("from", String.valueOf(ts)).param("to", String.valueOf(ts + 1000))
                        .param("windowMillis", "1000"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("UNKNOWN_METRIC"));

        // 非法窗口 -> ILLEGAL_WINDOW
        mvc.perform(get("/api/metrics/cpu/windows")
                        .param("from", String.valueOf(ts)).param("to", String.valueOf(ts + 1000))
                        .param("windowMillis", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("ILLEGAL_WINDOW"));

        // 越界范围 -> OUT_OF_RANGE (416)
        mvc.perform(get("/api/metrics/cpu/windows")
                        .param("from", String.valueOf(ts + 1000)).param("to", String.valueOf(ts))
                        .param("windowMillis", "1000"))
                .andExpect(status().isRequestedRangeNotSatisfiable())
                .andExpect(jsonPath("$.errorCode").value("OUT_OF_RANGE"));

        // 正常聚合查询
        mvc.perform(get("/api/metrics/cpu/windows")
                        .param("from", String.valueOf(ts)).param("to", String.valueOf(ts + 1000))
                        .param("windowMillis", "1000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].count").value(2))
                .andExpect(jsonPath("$[0].avg").value(15.0));

        // 注册规则 + 查询状态
        String rule = "{\"ruleId\":\"h1\",\"metric\":\"cpu\",\"operator\":\"GT\",\"threshold\":12,"
                + "\"windowMillis\":1000,\"minSamples\":2,\"breachThreshold\":1,\"recoverThreshold\":1}";
        mvc.perform(post("/api/alerts/rules").contentType(MediaType.APPLICATION_JSON).content(rule))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ruleId").value("h1"))
                .andExpect(jsonPath("$.state").value("FIRING"));

        // 非法规则 -> INVALID_RULE
        mvc.perform(post("/api/alerts/rules").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ruleId\":\"bad\",\"metric\":\"cpu\",\"windowMillis\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_RULE"));

        // 未知规则 -> UNKNOWN_RULE
        mvc.perform(get("/api/alerts/rules/nope"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("UNKNOWN_RULE"));
    }
}
