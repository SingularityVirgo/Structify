package com.feng.dsagent.studyroom.generation.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GenerationJobTimingTest {

    @Test
    void queueWaitIsNonNegativeElapsedSinceCreatedAt() {
        Instant created = Instant.parse("2026-11-01T04:00:00Z");
        Instant claimed = Instant.parse("2026-11-01T04:00:02.500Z");
        assertThat(GenerationJobTiming.queueWaitMs(Map.of("created_at", Timestamp.from(created)), claimed))
                .isEqualTo(2500L);
    }

    @Test
    void missingCreatedAtIsUnknownRatherThanZero() {
        assertThat(GenerationJobTiming.queueWaitMs(Map.of("id", "job-1"), Instant.parse("2026-11-01T04:00:00Z")))
                .isEqualTo(-1L);
    }
}
