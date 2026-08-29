package dev.hmcodes.jrap.crawl.service;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Deterministic page classification into the SRS taxonomy (FR-CRWL-1): home, about,
 * focus-and-scope, editorial-team, peer-review-policy, ethics, author-guidelines, fees,
 * open-access, indexing, announcements, archive, issue, article-landing, article-pdf,
 * other. Heuristics first (URL patterns, then slug/title keywords tolerant of odd
 * wording); the LLM fallback for unclassifiable pages arrives with the AI gateway
 * (Phase 4) and will only ever see pages this classifier marks 'other'.
 */
@Component
public class PageClassifier {

    private static final Pattern OJS_ISSUE_ARCHIVE = Pattern.compile("/issue/archive(/\\d+)?/?$");
    private static final Pattern OJS_ISSUE_VIEW = Pattern.compile("/issue/view/[^/]+/?$");
    private static final Pattern OJS_ARTICLE_VIEW = Pattern.compile("/article/view/[^/]+(/[^/]+)?/?$");
    private static final Pattern OJS_ARTICLE_DOWNLOAD = Pattern.compile("/article/download/.+");

    /**
     * Ordered keyword → type rules, most specific first: a slug like /about/editorialTeam
     * must classify as editorial-team, not about. NOTE: 'indexing'/'abstracting' — never a
     * bare 'index', which would misfire on every OJS /index.php/... path.
     *
     * <p>Matching is SEPARATOR-INSENSITIVE: both the keyword and the haystack are squashed
     * to bare letters/digits before comparison, so "peer-review", "Peer Review", and
     * "peer_review" all match one rule. Learned from a live site (WJCM) whose review-policy
     * page had the slug "review-proccess" (misspelt) and the spaced title "Peer Review
     * Process" — neither matched the old hyphen/concatenated patterns, producing a false
     * "publish your peer-review policy" roadmap action. 'review-proc' deliberately stops
     * before the ss so both "process" and the common "proccess" misspelling match.</p>
     */
    private static final java.util.List<Map.Entry<String, String>> KEYWORDS = java.util.List.of(
            Map.entry("peer-review", "peer-review-policy"),
            Map.entry("review-proc", "peer-review-policy"),   // process AND the misspelt proccess
            Map.entry("review-policy", "peer-review-policy"),
            Map.entry("refereeing", "peer-review-policy"),
            Map.entry("editorial", "editorial-team"),
            Map.entry("editors", "editorial-team"),
            Map.entry("board", "editorial-team"),
            Map.entry("ethic", "ethics"),            // tolerates 'ethics', 'ethical', misspelt slugs
            Map.entry("malpractice", "ethics"),
            Map.entry("plagiarism", "ethics"),
            Map.entry("author-guideline", "author-guidelines"),
            Map.entry("guideline", "author-guidelines"),
            Map.entry("submission", "author-guidelines"),
            Map.entry("instructions-for-authors", "author-guidelines"),
            Map.entry("focus", "focus-and-scope"),
            Map.entry("scope", "focus-and-scope"),
            Map.entry("aims", "focus-and-scope"),
            Map.entry("open-access", "open-access"),
            Map.entry("openaccess", "open-access"),
            Map.entry("licens", "open-access"),
            Map.entry("apc", "fees"),
            Map.entry("fee", "fees"),
            Map.entry("charge", "fees"),
            Map.entry("indexing", "indexing"),
            Map.entry("abstracting", "indexing"),
            Map.entry("announcement", "announcements"),
            Map.entry("news", "announcements"),
            Map.entry("archiv", "archive"),
            Map.entry("about", "about"),
            Map.entry("contact", "about"));

    public String classify(String url, String contentType, String pageTitle, String homepageUrl) {
        String path = pathOf(url).toLowerCase(Locale.ROOT);
        if (contentType != null && contentType.contains("pdf")) {
            return "article-pdf";
        }
        if (OJS_ARTICLE_DOWNLOAD.matcher(path).find()) {
            return "article-pdf";
        }
        if (OJS_ISSUE_ARCHIVE.matcher(path).find()) {
            return "archive";
        }
        if (OJS_ISSUE_VIEW.matcher(path).find()) {
            return "issue";
        }
        if (OJS_ARTICLE_VIEW.matcher(path).find()) {
            return "article-landing";
        }
        if (homepageUrl != null && normalisedEquals(url, homepageUrl)) {
            return "home";
        }
        if (path.isEmpty() || path.equals("/") || path.endsWith("/index")) {
            return "home";
        }
        // Squashed separately so no keyword can form accidentally across the path/title seam.
        String squashedPath = squash(path);
        String squashedTitle = squash(pageTitle == null ? "" : pageTitle);
        for (Map.Entry<String, String> entry : KEYWORDS) {
            String keyword = squash(entry.getKey());
            if (squashedPath.contains(keyword) || squashedTitle.contains(keyword)) {
                return entry.getValue();
            }
        }
        return "other";
    }

    /** Lowercase and strip everything but letters/digits — separator-insensitive matching. */
    private static String squash(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static String pathOf(String url) {
        try {
            String path = java.net.URI.create(url).getPath();
            return path == null ? "" : path;
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    private static boolean normalisedEquals(String a, String b) {
        return a.replaceAll("/+$", "").equalsIgnoreCase(b.replaceAll("/+$", ""));
    }
}
