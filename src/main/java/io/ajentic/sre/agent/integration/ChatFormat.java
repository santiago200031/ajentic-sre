package io.ajentic.sre.agent.integration;

import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Strategy for formatting an {@link IncidentSummary} across different chat protocols.
 * Formats: Markdown, Slack mrkdwn, Google Chat, Telegram, Teams Adaptive Card, and Generic JSON.
 */
@FunctionalInterface
public interface ChatFormat {

    String format(IncidentSummary summary);

    Pattern PULL_PATTERN = Pattern.compile("/pull/(\\d+)");
    ObjectMapper MAPPER = new ObjectMapper();

    ChatFormat MARKDOWN = summary -> {
        String pr = (summary.prLink() == null || summary.prLink().isBlank()) ? "N/A" : summary.prLink();
        return String.format(
                "* Incident: %s [%s]%n* Root Cause: %s%n* PR: %s%n* CI Status: %s%n* Summary: %s",
                summary.episodeId(),
                summary.status(),
                summary.rootCause(),
                pr,
                summary.ciStatus(),
                summary.summary());
    };

    ChatFormat SLACK_MRKDWN = summary -> {
        String prFormatted;
        if (summary.prLink() == null || summary.prLink().isBlank() || "N/A".equalsIgnoreCase(summary.prLink())) {
            prFormatted = "N/A";
        } else {
            Matcher m = PULL_PATTERN.matcher(summary.prLink());
            String label = m.find() ? "PR #" + m.group(1) : "PR Link";
            prFormatted = "<" + summary.prLink() + "|" + label + ">";
        }
        return String.format(
                "*Incident:* %s [*%s*]%n• *Root Cause:* %s%n• *PR:* %s%n• *CI Status:* %s%n• *Summary:* %s",
                summary.episodeId(),
                summary.status(),
                summary.rootCause(),
                prFormatted,
                summary.ciStatus(),
                summary.summary());
    };

    ChatFormat GOOGLE_CHAT = summary -> {
        String prFormatted;
        if (summary.prLink() == null || summary.prLink().isBlank() || "N/A".equalsIgnoreCase(summary.prLink())) {
            prFormatted = "N/A";
        } else {
            Matcher m = PULL_PATTERN.matcher(summary.prLink());
            String label = m.find() ? "PR #" + m.group(1) : "PR Link";
            prFormatted = "<" + summary.prLink() + "|" + label + ">";
        }
        return String.format(
                "*Incident:* %s [*%s*]%n• *Root Cause:* %s%n• *PR:* %s%n• *CI Status:* %s%n• *Summary:* %s",
                summary.episodeId(),
                summary.status(),
                summary.rootCause(),
                prFormatted,
                summary.ciStatus(),
                summary.summary());
    };

    ChatFormat TELEGRAM = summary -> {
        String prFormatted;
        if (summary.prLink() == null || summary.prLink().isBlank() || "N/A".equalsIgnoreCase(summary.prLink())) {
            prFormatted = "N/A";
        } else {
            Matcher m = PULL_PATTERN.matcher(summary.prLink());
            String label = m.find() ? "PR #" + m.group(1) : "PR Link";
            prFormatted = "[" + label + "](" + summary.prLink() + ")";
        }
        return String.format(
                "*Incident:* %s [%s]%n• *Root Cause:* %s%n• *PR:* %s%n• *CI Status:* %s%n• *Summary:* %s",
                summary.episodeId(),
                summary.status(),
                summary.rootCause(),
                prFormatted,
                summary.ciStatus(),
                summary.summary());
    };

    ChatFormat TEAMS_CARD = summary -> {
        String pr = (summary.prLink() == null || summary.prLink().isBlank()) ? "N/A" : summary.prLink();
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("type", "AdaptiveCard");
        card.put("version", "1.4");

        Map<String, Object> header = new LinkedHashMap<>();
        header.put("type", "TextBlock");
        header.put("text", "Incident " + summary.episodeId() + " [" + summary.status() + "]");
        header.put("weight", "Bolder");
        header.put("size", "Medium");

        List<Map<String, String>> facts = List.of(
                Map.of("title", "Root Cause", "value", summary.rootCause()),
                Map.of("title", "PR", "value", pr),
                Map.of("title", "CI Status", "value", summary.ciStatus()),
                Map.of("title", "Summary", "value", summary.summary())
        );
        Map<String, Object> factSet = Map.of("type", "FactSet", "facts", facts);

        card.put("body", List.of(header, factSet));
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(card);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize Teams card", e);
        }
    };

    ChatFormat GENERIC_JSON = summary -> {
        String pr = (summary.prLink() == null || summary.prLink().isBlank()) ? null : summary.prLink();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("episodeId", summary.episodeId());
        payload.put("incidentId", summary.incidentId());
        payload.put("rootCause", summary.rootCause());
        payload.put("prLink", pr);
        payload.put("ciStatus", summary.ciStatus());
        payload.put("summary", summary.summary());
        payload.put("status", summary.status());
        payload.put("evidence", summary.evidence());
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize generic json", e);
        }
    };
}
