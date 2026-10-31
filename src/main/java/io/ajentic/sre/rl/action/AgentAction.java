package io.ajentic.sre.rl.action;

public sealed interface AgentAction
        permits QueryTelemetry, ReadRunbook, ListRemediations, ApplyRemediation, DeclareDiagnosis, Idle,
        InspectRecentChanges, CorrelateAnomalies {

    String describe();
}
