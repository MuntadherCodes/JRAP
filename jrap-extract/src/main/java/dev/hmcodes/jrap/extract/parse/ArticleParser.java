package dev.hmcodes.jrap.extract.parse;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic article-landing parser (FR-EXT-2). OJS emits Highwire/DC meta tags
 * (citation_title, citation_author + citation_author_institution in document order,
 * citation_doi, dates) — the highest-confidence source. Structural fallbacks cover
 * abstract, displayed submission/acceptance dates, keywords, and the references list.
 */
@Component
public class ArticleParser {

    private static final Pattern SUBMITTED_PATTERN = Pattern.compile(
            "(?i)(?:received|submitted)\\s*[:\\-]?\\s*(\\d{4}-\\d{2}-\\d{2}|\\d{1,2}[ /.-]\\w+[ /.-]\\d{2,4}|\\w+\\s+\\d{1,2},?\\s+\\d{4})");
    private static final Pattern ACCEPTED_PATTERN = Pattern.compile(
            "(?i)accepted\\s*[:\\-]?\\s*(\\d{4}-\\d{2}-\\d{2}|\\d{1,2}[ /.-]\\w+[ /.-]\\d{2,4}|\\w+\\s+\\d{1,2},?\\s+\\d{4})");
    private static final Pattern PUBLISHED_PATTERN = Pattern.compile(
            "(?i)published\\s*[:\\-]?\\s*(\\d{4}-\\d{2}-\\d{2}|\\d{1,2}[ /.-]\\w+[ /.-]\\d{2,4}|\\w+\\s+\\d{1,2},?\\s+\\d{4})");
    private static final Pattern DOI_PATTERN = Pattern.compile("\\b10\\.\\d{4,9}/[^\\s\"<>]+");

    public ParsedArticle parse(Document document) {
        String title = meta(document, "citation_title", "DC.Title");
        List<ParsedArticle.ParsedAuthor> authors = parseAuthorsFromMeta(document);
        String doi = firstNonBlank(meta(document, "citation_doi", "DC.Identifier.DOI"),
                findDoiInText(document));
        String pages = pages(document);
        String published = meta(document, "citation_publication_date", "citation_date", "DC.Date.issued");
        String abstractText = abstractOf(document);
        List<String> keywords = keywords(document);
        List<String> references = references(document);
        String bodyText = document.text();
        String submitted = firstGroup(SUBMITTED_PATTERN, bodyText);
        String accepted = firstGroup(ACCEPTED_PATTERN, bodyText);
        if (published == null) {
            published = firstGroup(PUBLISHED_PATTERN, bodyText);
        }

        boolean metaRich = title != null && !authors.isEmpty();
        double confidence = metaRich ? 0.95 : (title != null ? 0.65 : 0.3);
        if (title == null) {
            // Last structural resort: page h1.
            Element h1 = document.selectFirst("h1");
            if (h1 != null && !h1.text().isBlank()) {
                title = h1.text().trim();
                confidence = Math.max(confidence, 0.5);
            }
        }
        return new ParsedArticle(title, doi, pages, abstractText, submitted, accepted, published,
                keywords, references, authors, "PARSER", confidence);
    }

    /** citation_author tags in document order, each owning the citation_author_institution tags that follow it. */
    private List<ParsedArticle.ParsedAuthor> parseAuthorsFromMeta(Document document) {
        List<ParsedArticle.ParsedAuthor> authors = new ArrayList<>();
        String currentAuthor = null;
        List<String> currentInstitutions = new ArrayList<>();
        for (Element metaTag : document.select("meta[name]")) {
            String name = metaTag.attr("name");
            String content = metaTag.attr("content").trim();
            if (content.isEmpty()) {
                continue;
            }
            if (name.equals("citation_author")) {
                flush(authors, currentAuthor, currentInstitutions);
                currentAuthor = content;
                currentInstitutions = new ArrayList<>();
            } else if (name.equals("citation_author_institution") && currentAuthor != null) {
                currentInstitutions.add(content);
            }
        }
        flush(authors, currentAuthor, currentInstitutions);
        return authors;
    }

    private static void flush(List<ParsedArticle.ParsedAuthor> authors, String author,
                              List<String> institutions) {
        if (author != null) {
            authors.add(new ParsedArticle.ParsedAuthor(author,
                    institutions.isEmpty() ? null : String.join("; ", institutions)));
        }
    }

    private static String pages(Document document) {
        String first = meta(document, "citation_firstpage");
        String last = meta(document, "citation_lastpage");
        if (first != null && last != null) {
            return first + "-" + last;
        }
        return first != null ? first : last;
    }

    private static String abstractOf(Document document) {
        String metaAbstract = meta(document, "citation_abstract", "DC.Description", "description");
        if (metaAbstract != null && metaAbstract.length() > 40) {
            return metaAbstract;
        }
        for (String selector : new String[]{"section.item.abstract", "div.item.abstract",
                "div.article-abstract", "div.abstract", "section.abstract"}) {
            Element section = document.selectFirst(selector);
            if (section != null) {
                String text = section.text().replaceFirst("(?i)^abstract:?\\s*", "").trim();
                if (text.length() > 40) {
                    return text;
                }
            }
        }
        return metaAbstract;
    }

    private static List<String> keywords(Document document) {
        List<String> keywords = new ArrayList<>();
        String citation = meta(document, "citation_keywords", "keywords");
        if (citation != null) {
            for (String keyword : citation.split("[;,]")) {
                if (!keyword.isBlank()) {
                    keywords.add(keyword.trim());
                }
            }
        }
        if (keywords.isEmpty()) {
            for (Element subject : document.select("meta[name=DC.Subject]")) {
                String content = subject.attr("content").trim();
                if (!content.isEmpty()) {
                    keywords.add(content);
                }
            }
        }
        return keywords;
    }

    private static List<String> references(Document document) {
        for (String selector : new String[]{"div.item.references", "section.item.references",
                "div.references", "section.references", "ol.references", "ul.references"}) {
            Element container = document.selectFirst(selector);
            if (container != null) {
                return referenceLines(container);
            }
        }
        // Fallback: a heading whose text is "References" followed by list/paragraph siblings.
        for (Element heading : document.select("h2, h3, h4")) {
            String text = heading.text().trim().toLowerCase(Locale.ROOT);
            if (text.equals("references") || text.equals("bibliography") || text.equals("المراجع")) {
                List<String> lines = new ArrayList<>();
                Element sibling = heading.nextElementSibling();
                while (sibling != null && lines.size() < 500) {
                    if (sibling.tagName().matches("h[1-6]")) {
                        break;
                    }
                    lines.addAll(referenceLines(sibling));
                    sibling = sibling.nextElementSibling();
                }
                return lines;
            }
        }
        return List.of();
    }

    private static List<String> referenceLines(Element container) {
        List<String> lines = new ArrayList<>();
        for (Element item : container.select("li, p")) {
            String text = item.text().trim();
            if (text.length() > 20) {
                lines.add(text);
            }
        }
        if (lines.isEmpty()) {
            String text = container.text().trim();
            if (text.length() > 20 && !text.equalsIgnoreCase("references")) {
                lines.add(text);
            }
        }
        return lines;
    }

    private static String findDoiInText(Document document) {
        Matcher matcher = DOI_PATTERN.matcher(document.text());
        return matcher.find() ? matcher.group().replaceAll("[).,;]+$", "") : null;
    }

    private static String meta(Document document, String... names) {
        for (String name : names) {
            Element tag = document.selectFirst("meta[name=" + name + "]");
            if (tag != null && !tag.attr("content").isBlank()) {
                return tag.attr("content").trim();
            }
        }
        return null;
    }

    private static String firstGroup(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
