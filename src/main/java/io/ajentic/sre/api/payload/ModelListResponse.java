package io.ajentic.sre.api.payload;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

@Schema(description = "OpenAI-compatible models list response.")
public record ModelListResponse(
        String object,
        List<ModelData> data
) {
    public record ModelData(
            String id,
            String object,
            long created,
            String owned_by
    ) {}
}
