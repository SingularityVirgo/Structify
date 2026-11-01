package com.feng.dsagent.studyroom.generation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Worker poll, in-process concurrency, and enablement. Job state stays in MySQL. */
@ConfigurationProperties("app.studyroom-generation")
public record GenerationProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("1000") long pollIntervalMs,
        @DefaultValue("2") int capacity
) {
    public GenerationProperties {
        if (pollIntervalMs < 1) {
            throw new IllegalArgumentException("app.studyroom-generation.poll-interval-ms must be >= 1");
        }
        if (capacity < 1) {
            throw new IllegalArgumentException("app.studyroom-generation.capacity must be >= 1");
        }
    }
}
