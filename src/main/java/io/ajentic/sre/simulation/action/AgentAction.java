package io.ajentic.sre.simulation.action;

public sealed interface AgentAction
        permits QueryTelemetry, ReadRunbook, ListRemediations, ApplyRemediation, DeclareDiagnosis, Idle,
        InspectRecentChanges, CorrelateAnomalies {

    String describe();
}
