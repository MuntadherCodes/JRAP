package dev.hmcodes.jrap.extract.parse;

import java.util.List;

/** Parser output before persistence (FR-EXT-2). */
public record ParsedArticle(String title, String doi, String pages, String abstractText,
                            String dateSubmitted, String dateAccepted, String datePublished,
                            List<String> keywords, List<String> references,
                            List<ParsedAuthor> authors, String method, double confidence) {

    public record ParsedAuthor(String name, String affiliation) {}
}
