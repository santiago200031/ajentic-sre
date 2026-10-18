package io.ajentic.sre.api.payload;

import io.ajentic.sre.schedule.store.ScheduleDefinition;

public record ScheduleView(
        String id,
        String name,
        String cron,
        String zone,
        boolean enabled,
        long version,
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
