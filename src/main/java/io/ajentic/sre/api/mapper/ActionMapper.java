package io.ajentic.sre.api.mapper;

import io.ajentic.sre.api.payload.ActionRequest;
import io.ajentic.sre.simulation.TelemetrySignal;
import io.ajentic.sre.simulation.action.ActionKind;
import io.ajentic.sre.simulation.action.AgentAction;
import io.ajentic.sre.simulation.action.ApplyRemediation;
import io.ajentic.sre.simulation.action.CorrelateAnomalies;
import io.ajentic.sre.simulation.action.DeclareDiagnosis;
import io.ajentic.sre.simulation.action.Idle;
import io.ajentic.sre.simulation.action.InspectRecentChanges;
import io.ajentic.sre.simulation.action.ListRemediations;
import io.ajentic.sre.simulation.action.QueryTelemetry;
import io.ajentic.sre.simulation.action.ReadRunbook;
import io.ajentic.sre.simulation.action.RemediationKind;

import java.util.List;

public final class ActionMapper {

    private ActionMapper() {
    }

    public static AgentAction toAction(ActionRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Action 'type' is required");
        }
        return switch (ActionKind.fromWireName(request.type())) {
            case QUERY_TELEMETRY -> new QueryTelemetry(
                    TelemetrySignal.fromWireName(request.signal()),
                    required(request.service(), "service"));
            case READ_RUNBOOK -> new ReadRunbook(required(request.runbookId(), "runbookId"));
            case LIST_REMEDIATIONS -> new ListRemediations();
            case APPLY_REMEDIATION -> new ApplyRemediation(
                    RemediationKind.fromWireName(request.remediation()),
                    required(request.service(), "service"));
            case DECLARE_DIAGNOSIS -> new DeclareDiagnosis(
                    required(request.service(), "service"),
                    required(request.rootCause(), "rootCause"));
            case INSPECT_RECENT_CHANGES -> new InspectRecentChanges(
                    required(request.service(), "service"),
                    request.window() == null ? 1440 : request.window());
            case CORRELATE_ANOMALIES -> new CorrelateAnomalies(
                    request.services() == null ? List.of() : request.services(),
                    request.window() == null ? 2 : request.window());
            case IDLE -> new Idle();
        };
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Action field '" + field + "' is required");
        }
        return value;
    }
}
