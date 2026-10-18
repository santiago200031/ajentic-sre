package io.ajentic.sre.schedule.cron;

import com.cronutils.model.Cron;
import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.model.time.ExecutionTime;
import com.cronutils.parser.CronParser;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;

/**
 * A five-field UNIX cron expression. Parsing, validation, matching and next-execution calculation are all delegated to
 * {@code com.cronutils} (already on the classpath through Quarkus Scheduler). This class only adapts the two
 * conventions the rest of the application relies on:
 *
 * <ul>
 *     <li>{@code ?} is accepted as a synonym for {@code *}, and</li>
 *     <li>{@link #canonical()} delegates to cron-utils so equivalent spellings (for example {@code 0 2 * * *} and
 *     {@code 00 02 * * *}) produce the same identity used for duplicate-schedule detection.</li>
 * </ul>
 */
public final class CronExpression {

    private static final CronParser PARSER =
            new CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX));

    private final Cron cron;
    private final ExecutionTime executionTime;

    private CronExpression(Cron cron) {
        this.cron = cron;
        this.executionTime = ExecutionTime.forCron(cron);
    }

    public static CronExpression parse(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new InvalidCronException("Cron expression is empty");
        }
        String normalized = expression.trim().replace('?', '*');
        if (normalized.split("\\s+").length != 5) {
            throw new InvalidCronException(
                    "Cron expression needs 5 fields (minute hour day-of-month month day-of-week): " + expression);
        }
        try {
            return new CronExpression(PARSER.parse(normalized).validate());
        } catch (RuntimeException exception) {
            throw new InvalidCronException("Unsupported cron expression: " + expression);
        }
    }

    public boolean matches(ZonedDateTime time) {
        return executionTime.isMatch(time);
    }

    public Optional<Instant> nextExecution(Instant after, ZoneId zone) {
        return executionTime.nextExecution(after.atZone(zone)).map(ZonedDateTime::toInstant);
    }

    public String canonical() {
        return cron.asString();
    }
}
