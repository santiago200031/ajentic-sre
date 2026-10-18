package io.ajentic.sre.agent.session;

import java.util.function.Predicate;

public record Goal(String description, Predicate<Conversation> completion) {
}
