package com.feng.dsagent.studyroom.generation.support;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.Map;

/** Queue wait from job created_at. Unknown timestamps return -1, not a fake zero wait. */
public final class GenerationJobTiming {
    private GenerationJobTiming() {}

    public static long queueWaitMs(Map<String, Object> job, Instant claimedAt) {
        Instant created = instant(job.get("created_at"));
        if (created == null) {
            created = instant(job.get("CREATED_AT"));
        }
        if (created == null || claimedAt == null) {
            return -1L;
        }
        return Math.max(0L, Duration.between(created, claimedAt).toMillis());
    }

    static Instant instant(Object value) {
        return switch (value) {
            case Instant instant -> instant;
            case Timestamp timestamp -> timestamp.toInstant();
            case OffsetDateTime offset -> offset.toInstant();
            case LocalDateTime local -> local.atZone(ZoneId.systemDefault()).toInstant();
            case Date date -> date.toInstant();
            case null, default -> null;
        };
    }
}
