package com.github.h3yow.metricsalertpipelinejava.web.dto;

import com.github.h3yow.metricsalertpipelinejava.model.Sample;

import java.util.List;

/** 批量接入请求。 */
public record IngestRequest(List<Sample> samples) {
}
