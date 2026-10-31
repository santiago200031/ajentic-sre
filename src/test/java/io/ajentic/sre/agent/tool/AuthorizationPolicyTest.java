package io.ajentic.sre.agent.tool;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The mutation policy and its legacy boolean alias.
 */
class AuthorizationPolicyTest {

    private ToolAuthorization policy(String policy, boolean allowMutations) {
        return new DefaultToolAuthorization(Optional.ofNullable(policy), allowMutations);
    }

    @Test
    void readToolsAreAlwaysAllowed() {
        ToolDescriptor read = new ToolDescriptor("read", "r", java.util.Map.of(), Capability.READ);
        assertEquals(AuthorizationDecision.ALLOW, policy(null, false).decide(read));
        assertEquals(AuthorizationDecision.ALLOW, policy("deny", false).decide(read));
    }

    @Test
    void policyWinsOverTheLegacyAlias() {
        ToolDescriptor mutate = new ToolDescriptor("write", "w", java.util.Map.of(), Capability.MUTATE);
        assertEquals(AuthorizationDecision.ALLOW, policy("allow", false).decide(mutate));
        assertEquals(AuthorizationDecision.DENY, policy("deny", true).decide(mutate));
        assertEquals(AuthorizationDecision.REQUIRE_APPROVAL, policy("require-approval", true).decide(mutate));
        assertEquals(AuthorizationDecision.REQUIRE_APPROVAL, policy("REQUIRE_APPROVAL", true).decide(mutate));
    }

    @Test
    void legacyAliasStillMapsToAllowAndDeny() {
        ToolDescriptor mutate = new ToolDescriptor("write", "w", java.util.Map.of(), Capability.MUTATE);
        assertEquals(AuthorizationDecision.ALLOW, policy(null, true).decide(mutate));
        assertEquals(AuthorizationDecision.DENY, policy(null, false).decide(mutate));
    }

    @Test
    void unknownPolicyIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> policy("maybe", true).decide(
                new ToolDescriptor("write", "w", java.util.Map.of(), Capability.MUTATE)));
    }
}
