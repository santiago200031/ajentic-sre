package io.ajentic.sre.schedule.engine;

import io.ajentic.sre.rl.episode.EpisodeResult;
import io.ajentic.sre.schedule.cron.CronExpression;
import io.ajentic.sre.schedule.drill.IncidentDrillRunner;
import io.ajentic.sre.schedule.store.ScheduleDefinition;
import io.ajentic.sre.schedule.store.ScheduleNotFoundException;
import io.ajentic.sre.schedule.store.ScheduleStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

@ApplicationScoped
public class Scheduler {

    private final ScheduleStore store;
    private final SchedulerClock clock;
    private final SchedulerSettings settings;
    private final IncidentDrillRunner drills;
    private final Map<String, ScheduledJob> adHocJobs = new ConcurrentHashMap<>();
    private final Executor executor;
    private final Semaphore permits;
    private final Map<String, Instant> lastRuns = new ConcurrentHashMap<>();
    private final Queue<DeferredRun> recoveryLane = new ConcurrentLinkedQueue<>();

    private volatile Instant lastTickedAt;

    @Inject
    public Scheduler(ScheduleStore store, SchedulerClock clock, SchedulerSettings settings, IncidentDrillRunner drills) {
        this(store, clock, settings, Executors.newVirtualThreadPerTaskExecutor(), settings.maxConcurrentRuns(), drills);
    }

    Scheduler(ScheduleStore store, SchedulerClock clock, SchedulerSettings settings, Executor executor,
            int maxConcurrentRuns) {
        this(store, clock, settings, executor, maxConcurrentRuns, null);
    }

    Scheduler(ScheduleStore store, SchedulerClock clock, SchedulerSettings settings, Executor executor,
            int maxConcurrentRuns, IncidentDrillRunner drills) {
        this.store = store;
        this.clock = clock;
        this.settings = settings;
        this.drills = drills;
        this.executor = executor;
        this.permits = new Semaphore(Math.max(1, maxConcurrentRuns));
        this.lastTickedAt = clock.now();
    }

    public ScheduleDefinition schedule(String name, String cron, String scenarioId) {
        return store.add(new ScheduleDefinition(
                null, name, cron, true, false, 0L, null, ZoneId.of(settings.zone()),
                scenarioId == null || scenarioId.isBlank() ? null : scenarioId));
    }

    /** In-memory schedules for tests and embedding; production schedules persist a scenario id instead. */
    public ScheduleDefinition schedule(String name, String cron, ScheduledJob job) {
        ScheduleDefinition definition = schedule(name, cron, (String) null);
        adHocJobs.put(definition.id(), job);
        return definition;
    }

    public EpisodeResult runNow(String id) {
        ScheduleDefinition definition = store.byId(id).orElseThrow(() -> new ScheduleNotFoundException(id));
        if (definition.scenarioId() != null) {
            return drills.drill(definition.scenarioId());
        }
        jobFor(definition).run(new ScheduleContext(definition.id(), definition.name(), clock.now()));
        return null;
    }

    private ScheduledJob jobFor(ScheduleDefinition definition) {
        if (definition.scenarioId() != null && drills != null) {
            return drills.job(definition.scenarioId());
        }
        return adHocJobs.getOrDefault(definition.id(), context -> { });
    }

    public synchronized TickReport tick() {
        Instant now = clock.now();
        List<RunRequest> due = new ArrayList<>();
        drainRecovery(now, due);
        collectDue(now, due);
        due.sort(Comparator
                .comparing(RunRequest::scheduledAt)
                .thenComparing(request -> request.definition().id()));

        List<String> executed = new ArrayList<>();
        List<String> deferred = new ArrayList<>();
        for (RunRequest request : due) {
            if (permits.tryAcquire()) {
                dispatch(request);
                executed.add(request.definition().id());
            } else {
                recover(request, now);
                deferred.add(request.definition().id());
            }
        }
        lastTickedAt = now;
        return new TickReport(now, executed, deferred, recoveryLane.size());
    }

    public Instant lastTickedAt() {
        return lastTickedAt;
    }

    public int recoveryBacklog() {
        return recoveryLane.size();
    }

    private void collectDue(Instant now, List<RunRequest> due) {
        for (ScheduleDefinition definition : store.all()) {
            if (!definition.enabled()) {
                continue;
            }
            Instant after = lastRuns.getOrDefault(definition.id(), now.minus(settings.tickInterval()));
            CronExpression.parse(definition.cron())
                    .nextExecution(after, definition.zone())
                    .filter(next -> !next.isAfter(now))
                    .ifPresent(next -> {
                        lastRuns.put(definition.id(), next);
                        due.add(new RunRequest(definition, next));
                    });
        }
    }

    private void drainRecovery(Instant now, List<RunRequest> due) {
        for (Iterator<DeferredRun> iterator = recoveryLane.iterator(); iterator.hasNext(); ) {
            DeferredRun deferred = iterator.next();
            if (!deferred.notBefore().isAfter(now)) {
                iterator.remove();
                due.add(deferred.request());
            }
        }
    }

    private void dispatch(RunRequest request) {
        executor.execute(() -> {
            try {
                jobFor(request.definition()).run(new ScheduleContext(
                        request.definition().id(),
                        request.definition().name(),
                        request.scheduledAt()));
            } catch (RuntimeException failure) {
                recover(request, clock.now());
            } finally {
                permits.release();
            }
        });
    }

    private void recover(RunRequest request, Instant now) {
        recoveryLane.add(new DeferredRun(request, now.plus(settings.recoveryDelay())));
    }

    record RunRequest(ScheduleDefinition definition, Instant scheduledAt) {
    }

    record DeferredRun(RunRequest request, Instant notBefore) {
    }
}
