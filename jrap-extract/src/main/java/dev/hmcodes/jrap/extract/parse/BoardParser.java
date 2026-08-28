package dev.hmcodes.jrap.extract.parse;

import dev.hmcodes.jrap.extract.util.Countries;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Deterministic editorial-board parser (FR-EXT-1). Walks the page's paragraph/list
 * entries under role headings; per entry: name (first comma segment that looks like a
 * person), institution (middle segments), country (matched against the country list),
 * and ORCID/Scholar/Scopus links. Confidence reflects how much structure matched;
 * pages this parser can't read fall back to the LLM via the gateway (CON-4).
 */
@Component
public class BoardParser {

    private static final Pattern NAME_PATTERN = Pattern.compile(
            "^(?:(?:Prof|Professor|Dr|Assist|Assoc|Asst|Mr|Mrs|Ms|Eng)\\.?\\s+)*"
                    + "\\p{Lu}[\\p{L}.'-]+(?:\\s+\\p{Lu}[\\p{L}.'-]+){1,4}\\.?$");
    private static final Set<String> ROLE_KEYWORDS = Set.of(
            "editor", "chief", "associate", "managing", "member", "secretary", "advisor",
            "advisory", "board", "reviewer", "director", "assistant");

    public List<ParsedMember> parse(Document document) {
        List<ParsedMember> members = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        String currentRole = null;
        for (Element element : document.select("h1, h2, h3, h4, h5, p, li")) {
            String text = element.text().trim();
            if (text.isEmpty()) {
                continue;
            }
            if (element.tagName().startsWith("h")) {
                currentRole = looksLikeRole(text) ? text : currentRole;
                continue;
            }
            ParsedMember member = parseEntry(element, text, currentRole);
            if (member != null && seen.add(member.name().toLowerCase(Locale.ROOT) + "|" + member.role())) {
                members.add(member);
            }
        }
        return members;
    }

    private ParsedMember parseEntry(Element element, String text, String currentRole) {
        // Segment on a link-free clone: anchor labels like "ORCID" must never leak into
        // the institution/country segments. (An entry whose name exists ONLY inside an
        // anchor is intentionally dropped; the <strong> fallback covers OJS layouts.)
        Element clean = element.clone();
        clean.select("a").remove();
        String cleanText = clean.text().trim();
        if (cleanText.isEmpty()) {
            return null;
        }
        String[] segments = cleanText.split(",");
        String candidateName = segments[0].trim();
        // Some layouts put the name in a <strong>/<b> child instead of the first segment.
        Element bold = element.selectFirst("strong, b");
        if (bold != null && looksLikeName(bold.text().trim())) {
            candidateName = bold.text().trim();
        }
        if (!looksLikeName(candidateName)) {
            return null;
        }
        String country = Countries.find(cleanText).orElse(null);
        String institution = extractInstitution(segments, candidateName, country);
        List<String> links = new ArrayList<>();
        for (Element anchor : element.select("a[href]")) {
            String href = anchor.attr("abs:href");
            String lower = href.toLowerCase(Locale.ROOT);
            if (lower.contains("orcid.org") || lower.contains("scholar.google")
                    || lower.contains("scopus.com") || lower.contains("researchgate.net")) {
                links.add(href);
            }
        }
        double confidence;
        if (country != null && institution != null) {
            confidence = 0.9;
        } else if (institution != null || country != null) {
            confidence = 0.75;
        } else {
            confidence = 0.55;
        }
        return new ParsedMember(candidateName, currentRole, institution, country, links,
                confidence, truncate(text));
    }

    private static String extractInstitution(String[] segments, String name, String country) {
        List<String> middle = new ArrayList<>();
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i].trim();
            if (segment.isEmpty() || segment.equals(name)) {
                continue;
            }
            if (i == 0) {
                continue; // name segment
            }
            if (country != null && segment.replaceAll("[^\\p{L}\\s]", "").trim()
                    .equalsIgnoreCase(country)) {
                continue; // the pure country segment (an institution NAMED after a country stays)
            }
            middle.add(segment);
        }
        return middle.isEmpty() ? null : String.join(", ", middle);
    }

    public static boolean looksLikeName(String candidate) {
        return candidate != null && candidate.length() <= 60
                && !candidate.matches(".*\\d.*")
                && NAME_PATTERN.matcher(candidate).matches();
    }

    private static boolean looksLikeRole(String heading) {
        String lower = heading.toLowerCase(Locale.ROOT);
        return ROLE_KEYWORDS.stream().anyMatch(lower::contains);
    }

    private static String truncate(String text) {
        return text.length() > 300 ? text.substring(0, 300) : text;
    }
}
