package io.ajentic.sre.agent.tool.builtin;

import io.ajentic.sre.rl.ObservedSignal;

import java.util.List;
import java.util.stream.Collectors;

final class ToolText {

    private ToolText() {
    }

    static String format(List<ObservedSignal> signals) {
        return signals.stream()
                .map(signal -> "[%s] %s: %s".formatted(signal.signal(), signal.service(), signal.summary()))
                .collect(Collectors.joining("\n"));
    }
}
