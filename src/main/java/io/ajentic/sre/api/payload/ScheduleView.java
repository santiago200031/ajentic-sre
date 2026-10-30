package io.ajentic.sre.api.payload;

import io.ajentic.sre.schedule.store.ScheduleDefinition;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * View of a scheduled drill: its identity, cron schedule, scope, and state.
 */
@Schema(description = "View of a scheduled drill.")
public record ScheduleView(
        @Schema(description = "Schedule id.", example = "s1a2b3c4-...")
        String id,

        @Schema(description = "Schedule name.", example = "hourly-checkout-drill")
        String name,

        @Schema(description = "Cron expression.", example = "0 * * * *")
        String cron,

        @Schema(description = "Time zone of the schedule.", example = "UTC")
        String zone,

        @Schema(description = "Whether the schedule is enabled.", example = "true")
        boolean enabled,

        @Schema(description = "Version, bumped on each change.", example = "1")
        long version,

        @Schema(description = "Loop group id the schedule belongs to.")
        String loopGroupId) {

    public static ScheduleView from(ScheduleDefinition definition) {
        return new ScheduleView(
                definition.id(),
                definition.name(),
                definition.cron(),
                definition.zone().getId(),
                definition.enabled(),
                definition.version(),
                definition.loopGroupId());
    }
}
