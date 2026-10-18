package io.ajentic.sre.rl.simulation;

import java.time.Instant;

record DeployRecord(String service, String version, Instant deployedAt) {
}
