package io.ajentic.sre.simulation;

import java.time.Instant;

/**
 * A past change to a service: a deploy, a config edit, a feature-flag flip or a quota change. {@code diff} holds the
 * git-style change lines rendered by the DEPLOYS signal and {@code inspect_recent_changes}.
 */
public record DeployRecord(String service, String kind, String summary, Instant occurredAt, String diff) {

    public static final String KIND_DEPLOY = "DEPLOY";
    public static final String KIND_CONFIG = "CONFIG";
    public static final String KIND_FEATURE_FLAG = "FEATURE_FLAG";
    public static final String KIND_QUOTA = "QUOTA";

    public DeployRecord(String service, String kind, String summary, Instant occurredAt) {
        this(service, kind, summary, occurredAt, "");
    }
}
