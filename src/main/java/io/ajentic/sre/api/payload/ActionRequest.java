package io.ajentic.sre.api.payload;

import jakarta.validation.constraints.NotNull;

public record ActionRequest(
        @NotNull String type,
        String signal,
        String service,
        String runbookId,
        String remediation,
        String rootCause) {
}
