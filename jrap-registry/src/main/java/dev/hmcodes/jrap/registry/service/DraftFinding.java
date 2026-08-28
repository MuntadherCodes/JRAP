package dev.hmcodes.jrap.registry.service;

import dev.hmcodes.jrap.registry.domain.Finding;

import java.util.List;
import java.util.UUID;

/** A detector's proposed finding before persistence, with the evidence backing it. */
public record DraftFinding(String code, Finding.Severity severity, String title,
                           String description, List<UUID> evidenceItemIds) {
}
