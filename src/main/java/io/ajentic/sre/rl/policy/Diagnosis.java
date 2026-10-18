package io.ajentic.sre.rl.policy;

import io.ajentic.sre.rl.action.RemediationKind;

public record Diagnosis(String component, String rootCause, RemediationKind remediation) {
}
