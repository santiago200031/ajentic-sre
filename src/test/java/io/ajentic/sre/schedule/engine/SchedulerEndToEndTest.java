package io.ajentic.sre.schedule.engine;

import io.ajentic.sre.schedule.cron.InvalidCronException;
import io.ajentic.sre.schedule.store.DuplicateScheduleException;
import io.ajentic.sre.schedule.store.InMemoryScheduleStore;
import io.ajentic.sre.schedule.store.ScheduleDefinition;
import io.ajentic.sre.schedule.store.ScheduleNotFoundException;
import io.ajentic.sre.schedule.store.StaleScheduleException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SchedulerEndToEndTest {

    private static final Instant MONDAY_10 = Instant.parse("2026-01-05T10:00:00Z");

    private final MutableClock clock = new MutableClock(MONDAY_10);
    private final ManualExecutor executor = new ManualExecutor();
    private final InMemoryScheduleStore store = new InMemoryScheduleStore();

    @Test
    void appliesStepsToNamedDayRanges() {
        Scheduler scheduler = scheduler(4, Duration.ofMinutes(1));
        List<Instant> runs = new ArrayList<>();
        scheduler.schedule("weekday-biweekly", "0 10 * * mon-fri/2", context -> runs.add(context.scheduledAt()));

        for (int day = 0; day < 7; day++) {
            clock.set(MONDAY_10.plus(Duration.ofDays(day)));
            scheduler.tick();
            executor.runAll();
        }

        assertEquals(
                List.of("2026-01-05T10:00:00Z", "2026-01-07T10:00:00Z", "2026-01-09T10:00:00Z"),
                runs.stream().map(Instant::toString).toList());
    }

    @Test
    void appliesStepsToNamedMonthRanges() {
        Scheduler scheduler = scheduler(4, Duration.ofMinutes(1));
        List<Instant> runs = new ArrayList<>();
        scheduler.schedule("quarterly", "0 0 1 jan-dec/3 *", context -> runs.add(context.scheduledAt()));

        for (int month = 1; month <= 12; month++) {
            clock.set(Instant.parse("2026-%02d-01T00:00:00Z".formatted(month)));
            scheduler.tick();
            executor.runAll();
        }

        assertEquals(
                List.of("2026-01-01T00:00:00Z", "2026-04-01T00:00:00Z",
                        "2026-07-01T00:00:00Z", "2026-10-01T00:00:00Z"),
                runs.stream().map(Instant::toString).toList());
    }

    @Test
    void expandsHourlyStepsFromTheEarliestMinute() {
        Scheduler scheduler = scheduler(4, Duration.ofMinutes(1));
        List<Instant> runs = new ArrayList<>();
        scheduler.schedule("quarter-hourly", "*/15 * * * *", context -> runs.add(context.scheduledAt()));

        for (int minute = 0; minute < 60; minute++) {
            clock.set(Instant.parse("2026-01-05T10:00:00Z").plus(Duration.ofMinutes(minute)));
            scheduler.tick();
            executor.runAll();
        }

        assertEquals(
                List.of("2026-01-05T10:00:00Z", "2026-01-05T10:15:00Z",
                        "2026-01-05T10:30:00Z", "2026-01-05T10:45:00Z"),
                runs.stream().map(Instant::toString).toList());
    }

    @Test
    void rejectsDuplicateSchedulesAndAllowsReadditionAfterStop() {
        Scheduler scheduler = scheduler(4, Duration.ofMinutes(1));
        ScheduleDefinition first = scheduler.schedule("nightly-report", "0 2 * * *", context -> { });

        assertThrows(DuplicateScheduleException.class,
                () -> scheduler.schedule("nightly-report", "0 2 * * *", context -> { }));

        store.stop(first.id());
        ScheduleDefinition readded = scheduler.schedule("nightly-report", "0 2 * * *", context -> { });

        assertEquals(1, store.all().size());
        assertEquals(readded.identity(), first.identity());
        assertEquals(1L, readded.version());
    }

    @Test
    void resolvesSchedulesByNameAndFallsBackAfterStop() {
        Scheduler scheduler = scheduler(4, Duration.ofMinutes(1));
        ScheduleDefinition first = scheduler.schedule("alpha", "0 1 * * *", context -> { });
        ScheduleDefinition second = scheduler.schedule("alpha", "0 2 * * *", context -> { });

        assertEquals(first.id(), store.byName("alpha").orElseThrow().id());

        store.stop(first.id());

        assertEquals(second.id(), store.byName("alpha").orElseThrow().id());
        assertEquals(1, store.all().size());
    }

    @Test
    void staleWritesCannotRevertOrResurrectStoppedSchedules() {
        Scheduler scheduler = scheduler(4, Duration.ofMinutes(1));
        ScheduleDefinition schedule = scheduler.schedule("nightly-report", "0 2 * * *", context -> { });
        long version = schedule.version();

        store.update(schedule.id(), version, definition -> definition.withCron("30 2 * * *"));

        assertThrows(StaleScheduleException.class,
                () -> store.update(schedule.id(), version, definition -> definition.withCron("0 3 * * *")));

        store.stop(schedule.id());

        assertThrows(ScheduleNotFoundException.class,
                () -> store.update(schedule.id(), version + 1, definition -> definition.withCron("0 4 * * *")));
    }

    @Test
    void failedRunsAreRequeuedAndRetried() {
        Duration recoveryDelay = Duration.ofMinutes(1);
        Scheduler scheduler = scheduler(4, recoveryDelay);
        AtomicInteger attempts = new AtomicInteger();
        scheduler.schedule("flaky", "0 10 * * *", context -> {
            if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("transient failure");
            }
        });

        scheduler.tick();
        executor.runAll();
        assertEquals(1, attempts.get());
        assertEquals(1, scheduler.recoveryBacklog());

        clock.advance(recoveryDelay.plusSeconds(1));
        scheduler.tick();
        executor.runAll();

        assertEquals(2, attempts.get());
        assertEquals(0, scheduler.recoveryBacklog());
    }

    @Test
    void capsConcurrencyAndDefersTheRemainderToTheRecoveryLane() {
        Scheduler scheduler = scheduler(2, Duration.ofMinutes(1));
        AtomicInteger started = new AtomicInteger();
        for (int index = 0; index < 3; index++) {
            scheduler.schedule("task-" + index, "0 10 * * *", context -> started.incrementAndGet());
        }

        TickReport first = scheduler.tick();
        assertEquals(2, first.executed().size());
        assertEquals(1, first.deferred().size());
        executor.runAll();
        assertEquals(2, started.get());

        clock.advance(Duration.ofMinutes(2));
        scheduler.tick();
        executor.runAll();

        assertEquals(3, started.get());
    }

    @Test
    void rejectsInvalidCronExpressions() {
        Scheduler scheduler = scheduler(4, Duration.ofMinutes(1));

        assertThrows(InvalidCronException.class,
                () -> scheduler.schedule("bad", "not a cron", context -> { }));
        assertThrows(InvalidCronException.class,
                () -> scheduler.schedule("bad-day", "0 0 32 * *", context -> { }));
        assertThrows(InvalidCronException.class,
                () -> scheduler.schedule("too-few", "* * *", context -> { }));
    }

    private Scheduler scheduler(int maxConcurrentRuns, Duration recoveryDelay) {
        return new Scheduler(store, clock, new TestSettings(maxConcurrentRuns, recoveryDelay), executor,
                maxConcurrentRuns);
    }

    private record TestSettings(int maxConcurrentRuns, Duration recoveryDelay) implements SchedulerSettings {

        @Override
        public Duration tickInterval() {
            return Duration.ofSeconds(1);
        }

        @Override
        public String zone() {
            return "UTC";
        }
    }

    private static final class MutableClock implements SchedulerClock {

        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public Instant now() {
            return now;
        }

        void set(Instant instant) {
            this.now = instant;
        }

        void advance(Duration duration) {
            this.now = now.plus(duration);
        }
    }

    private static final class ManualExecutor implements Executor {

        private final List<Runnable> tasks = new ArrayList<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        void runAll() {
            List<Runnable> pending = List.copyOf(tasks);
            tasks.clear();
            pending.forEach(Runnable::run);
        }
    }
}
