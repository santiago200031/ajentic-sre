package io.ajentic.sre.cli;

import io.ajentic.sre.schedule.store.ScheduleDefinition;
import io.ajentic.sre.schedule.store.ScheduleStore;
import io.ajentic.sre.schedule.engine.Scheduler;
import io.ajentic.sre.schedule.engine.TickReport;
import io.quarkus.arc.Unremovable;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Unremovable
@Command(name = "schedules", mixinStandardHelpOptions = true,
        description = "Manage scheduled incident drills.")
public class SchedulesCommand implements Runnable {

    @Inject
    Scheduler scheduler;

    @Inject
    ScheduleStore store;

    @Option(names = "--add", description = "Name of the schedule to create.")
    String add;

    @Option(names = "--cron", description = "Cron expression for --add.")
    String cron;

    @Option(names = "--scenario", description = "Scenario to drill for --add.")
    String scenario;

    @Option(names = "--tick", description = "Run a single scheduler tick.")
    boolean tick;

    @Option(names = "--run", description = "Run a schedule immediately by name.")
    String run;

    @Override
    public void run() {
        if (tick) {
            TickReport report = scheduler.tick();
            System.out.printf("tick executed=%d deferred=%d backlog=%d%n",
                    report.executed().size(), report.deferred().size(), report.recoveryBacklog());
        }
        if (add != null && cron != null && scenario != null) {
            scheduler.schedule(add, cron, scenario);
        }
        if (run != null) {
            store.byName(run).ifPresent(definition -> scheduler.runNow(definition.id()));
        }
        store.all().forEach(this::print);
    }

    private void print(ScheduleDefinition definition) {
        System.out.printf("%-24s %-18s enabled=%s%n", definition.name(), definition.cron(), definition.enabled());
    }
}
