package dev.hmcodes.jrap.registry.service;

import dev.hmcodes.jrap.integrations.dto.JournalSourceIdentity;
import dev.hmcodes.jrap.integrations.dto.SourceAvailability;
import dev.hmcodes.jrap.registry.domain.Finding;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Deterministic identity-inconsistency detector (FR-JRN-2). Given what each source
 * states, emits category "identity" findings — the registry-level face of RF-09.
 * Same inputs, same rubric version, same findings (SRS §3.1.6 preamble).
 */
@Component
public class IdentityConsistencyChecker {

    public static final String DETECTOR_VERSION = "identity/1.0.0";
    public static final String CATEGORY = "identity";

    /** One source's statement plus the evidence item recording it (null for absent sources). */
    public record SourceStatement(JournalSourceIdentity identity, SourceAvailability availability,
                                  UUID evidenceItemId) {}

    public List<DraftFinding> check(String registeredIssn, Map<String, SourceStatement> statements) {
        List<DraftFinding> findings = new ArrayList<>();
        Map<String, SourceStatement> ok = statements.entrySet().stream()
                .filter(e -> e.getValue().availability() == SourceAvailability.OK)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (a, b) -> a, LinkedHashMap::new));

        swappedIssns(ok, findings);
        issnLMismatch(ok, findings);
        fieldMismatch(ok, findings, "publisher", "IDENTITY_PUBLISHER_MISMATCH",
                s -> s.identity().publisher());
        fieldMismatch(ok, findings, "title", "IDENTITY_TITLE_MISMATCH",
                s -> s.identity().title());
        siteIssnMismatch(registeredIssn, ok, findings);
        absenceAndUnavailability(statements, findings);
        return findings;
    }

    private void swappedIssns(Map<String, SourceStatement> ok, List<DraftFinding> findings) {
        List<SourceStatement> typed = ok.values().stream()
                .filter(s -> s.identity().issnPrint() != null && s.identity().issnOnline() != null)
                .toList();
        for (int i = 0; i < typed.size(); i++) {
            for (int j = i + 1; j < typed.size(); j++) {
                SourceStatement a = typed.get(i);
                SourceStatement b = typed.get(j);
                boolean swapped = a.identity().issnPrint().equals(b.identity().issnOnline())
                        && a.identity().issnOnline().equals(b.identity().issnPrint())
                        && !a.identity().issnPrint().equals(a.identity().issnOnline());
                if (swapped) {
                    findings.add(new DraftFinding(
                            "IDENTITY_SWAPPED_ISSNS", Finding.Severity.HIGH,
                            "Print and online ISSNs are swapped between sources",
                            "%s states print=%s / online=%s, but %s states print=%s / online=%s — the two sources disagree on which ISSN is which."
                                    .formatted(a.identity().source(), a.identity().issnPrint(), a.identity().issnOnline(),
                                            b.identity().source(), b.identity().issnPrint(), b.identity().issnOnline()),
                            evidence(a, b)));
                }
            }
        }
    }

    private void issnLMismatch(Map<String, SourceStatement> ok, List<DraftFinding> findings) {
        Map<String, List<SourceStatement>> byIssnL = ok.values().stream()
                .filter(s -> s.identity().issnL() != null)
                .collect(Collectors.groupingBy(s -> s.identity().issnL(), LinkedHashMap::new, Collectors.toList()));
        if (byIssnL.size() > 1) {
            String detail = byIssnL.entrySet().stream()
                    .map(e -> e.getKey() + " (" + e.getValue().stream()
                            .map(s -> s.identity().source()).collect(Collectors.joining(", ")) + ")")
                    .collect(Collectors.joining("; "));
            findings.add(new DraftFinding(
                    "IDENTITY_ISSN_L_MISMATCH", Finding.Severity.HIGH,
                    "Sources disagree on the linking ISSN (ISSN-L)",
                    "Distinct ISSN-L values are stated: " + detail + ".",
                    byIssnL.values().stream().flatMap(List::stream).map(SourceStatement::evidenceItemId)
                            .filter(Objects::nonNull).distinct().toList()));
        }
    }

    private interface FieldExtractor {
        String get(SourceStatement statement);
    }

    private void fieldMismatch(Map<String, SourceStatement> ok, List<DraftFinding> findings,
                               String fieldName, String code, FieldExtractor extractor) {
        List<SourceStatement> stated = ok.values().stream()
                .filter(s -> extractor.get(s) != null && !extractor.get(s).isBlank())
                .toList();
        Set<SourceStatement> divergent = new LinkedHashSet<>();
        for (int i = 0; i < stated.size(); i++) {
            for (int j = i + 1; j < stated.size(); j++) {
                String a = normalise(extractor.get(stated.get(i)));
                String b = normalise(extractor.get(stated.get(j)));
                if (!a.contains(b) && !b.contains(a)) {
                    divergent.add(stated.get(i));
                    divergent.add(stated.get(j));
                }
            }
        }
        if (!divergent.isEmpty()) {
            String detail = divergent.stream()
                    .map(s -> s.identity().source() + ": \"" + extractor.get(s) + "\"")
                    .collect(Collectors.joining("; "));
            findings.add(new DraftFinding(
                    code, Finding.Severity.MEDIUM,
                    "Sources disagree on the journal's " + fieldName,
                    "Conflicting " + fieldName + " values: " + detail + ".",
                    divergent.stream().map(SourceStatement::evidenceItemId)
                            .filter(Objects::nonNull).distinct().toList()));
        }
    }

    private void siteIssnMismatch(String registeredIssn, Map<String, SourceStatement> ok,
                                  List<DraftFinding> findings) {
        SourceStatement site = ok.get("SITE");
        if (site == null || registeredIssn == null) {
            return;
        }
        List<String> siteIssns = site.identity().issns();
        if (siteIssns != null && !siteIssns.isEmpty() && !siteIssns.contains(registeredIssn)) {
            findings.add(new DraftFinding(
                    "IDENTITY_SITE_ISSN_MISMATCH", Finding.Severity.MEDIUM,
                    "The journal's site does not display the registered ISSN",
                    "The homepage displays ISSN(s) " + String.join(", ", siteIssns)
                            + " but not the registered ISSN " + registeredIssn + ".",
                    evidenceOf(site)));
        }
    }

    private void absenceAndUnavailability(Map<String, SourceStatement> statements,
                                          List<DraftFinding> findings) {
        statements.forEach((source, statement) -> {
            if (statement.availability() == SourceAvailability.NOT_FOUND) {
                Finding.Severity severity = switch (source) {
                    case "CROSSREF", "OPENALEX" -> Finding.Severity.LOW;
                    default -> Finding.Severity.INFO;
                };
                findings.add(new DraftFinding(
                        "IDENTITY_NOT_IN_" + source, severity,
                        "Journal not found in " + source,
                        source + " has no record for this journal's ISSN.",
                        evidenceOf(statement)));
            } else if (statement.availability() == SourceAvailability.UNAVAILABLE) {
                findings.add(new DraftFinding(
                        "IDENTITY_SOURCE_UNAVAILABLE_" + source, Finding.Severity.INFO,
                        "UNCLEAR — " + source + " unavailable",
                        source + " could not be reached during resolution; identity checks involving it are UNCLEAR (source unavailable). They will be retried on the next audit.",
                        evidenceOf(statement)));
            }
        });
    }

    private static List<UUID> evidence(SourceStatement a, SourceStatement b) {
        return List.of(a, b).stream().map(SourceStatement::evidenceItemId)
                .filter(Objects::nonNull).distinct().toList();
    }

    private static List<UUID> evidenceOf(SourceStatement statement) {
        return statement.evidenceItemId() == null ? List.of() : List.of(statement.evidenceItemId());
    }

    static String normalise(String value) {
        String decomposed = Normalizer.normalize(value, Normalizer.Form.NFKD);
        return decomposed.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{Alnum}\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
