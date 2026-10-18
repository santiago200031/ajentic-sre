package io.ajentic.sre.schedule.engine;

import java.time.Instant;

public record ScheduleContext(String scheduleId, String scheduleName, Instant scheduledAt) {
}
