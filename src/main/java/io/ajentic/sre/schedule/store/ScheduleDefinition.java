package io.ajentic.sre.schedule.store;

import io.ajentic.sre.schedule.cron.CronExpression;

import java.time.ZoneId;

public record ScheduleDefinition(
        String id,
        String name,
        String cron,
        boolean enabled,
        boolean stopped,
        long version,
        String loopGroupId,
        ZoneId zone,
        String scenarioId) {

    public ScheduleDefinition {
        CronExpression.parse(cron);
        zone = zone == null ? ZoneId.of("UTC") : zone;
    }

    public String identity() {
        return name + "|" + CronExpression.parse(cron).canonical();
    }

    public ScheduleDefinition withCron(String newCron) {
        return new ScheduleDefinition(id, name, newCron, enabled, stopped, version, loopGroupId, zone, scenarioId);
    }
}
