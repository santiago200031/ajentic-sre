package io.ajentic.sre.simulation.playbook;

import io.ajentic.sre.simulation.action.RemediationKind;

public record Diagnosis(String component, String rootCause, RemediationKind remediation) {
}
