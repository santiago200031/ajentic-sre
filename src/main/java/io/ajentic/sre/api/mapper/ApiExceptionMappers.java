package io.ajentic.sre.api.mapper;

import io.ajentic.sre.agent.session.UnknownSessionException;
import io.ajentic.sre.rl.episode.UnknownEpisodeException;
import io.ajentic.sre.rl.simulation.UnknownScenarioException;
import io.ajentic.sre.schedule.cron.InvalidCronException;
import io.ajentic.sre.schedule.store.DuplicateScheduleException;
import io.ajentic.sre.schedule.store.ScheduleNotFoundException;
import io.ajentic.sre.schedule.store.StaleScheduleException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

import java.util.Map;

/**
 * One mapper per status family. RESTEasy picks the most specific mapped type, so a single method can cover every
 * domain exception that shares a status.
 */
@ApplicationScoped
public class ApiExceptionMappers {

    @ServerExceptionMapper({IllegalArgumentException.class, InvalidCronException.class})
    public Response badRequest(RuntimeException exception) {
        return error(Response.Status.BAD_REQUEST, exception);
    }

    @ServerExceptionMapper({UnknownScenarioException.class, UnknownEpisodeException.class,
            UnknownSessionException.class, ScheduleNotFoundException.class})
    public Response notFound(RuntimeException exception) {
        return error(Response.Status.NOT_FOUND, exception);
    }

    @ServerExceptionMapper({DuplicateScheduleException.class, StaleScheduleException.class})
    public Response conflict(RuntimeException exception) {
        return error(Response.Status.CONFLICT, exception);
    }

    private static Response error(Response.Status status, RuntimeException exception) {
        String message = exception.getMessage() == null
                ? exception.getClass().getSimpleName()
                : exception.getMessage();
        return Response.status(status).entity(Map.of("error", message)).build();
    }
}
