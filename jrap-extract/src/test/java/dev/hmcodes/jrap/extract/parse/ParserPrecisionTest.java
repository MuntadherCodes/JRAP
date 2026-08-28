package dev.hmcodes.jrap.extract.parse;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NFR-AI-1 precision gate: against labelled fixture pages, board-member and authorship
 * extraction must reach >= 95% precision before any release. These fixtures are the
 * seed of the labelled test set; every future parser change must keep this bar.
 */
class ParserPrecisionTest {

    private final BoardParser boardParser = new BoardParser();
    private final ArticleParser articleParser = new ArticleParser();

    @Test
    void boardExtractionPrecisionAtLeast95Percent() throws IOException {
        Document document = load("/fixtures/editorial-team.html");
        List<ParsedMember> extracted = boardParser.parse(document);

        Set<String> labelled = Set.of(
                "Prof. Ali Hassan|University of Baghdad|Iraq",
                "Dr. Sara Ahmed|University of Jordan|Jordan",
                "Dr. Omar Khalid|Cairo University|Egypt",
                "Prof. Fatima Noor|King Saud University|Saudi Arabia",
                "Dr. John Smith|University of Manchester|United Kingdom",
                "Dr. Chen Wei|Tsinghua University|China",
                "Prof. Maria Rossi|Sapienza University of Rome|Italy",
                "Dr. Ahmed Al-Mansoori|United Arab Emirates University|United Arab Emirates");

        Set<String> extractedKeys = extracted.stream()
                .map(m -> m.name() + "|" + m.institution() + "|" + m.country())
                .collect(Collectors.toSet());

        long truePositives = extractedKeys.stream().filter(labelled::contains).count();
        double precision = extracted.isEmpty() ? 0 : (double) truePositives / extracted.size();
        double recall = (double) truePositives / labelled.size();

        assertThat(precision)
                .as("board extraction precision (NFR-AI-1 requires >= 0.95); extracted=%s", extractedKeys)
                .isGreaterThanOrEqualTo(0.95);
        assertThat(recall).as("board extraction recall").isGreaterThanOrEqualTo(0.85);
    }

    @Test
    void authorshipExtractionPrecisionAtLeast95Percent() throws IOException {
        Document document = load("/fixtures/article-landing.html");
        ParsedArticle parsed = articleParser.parse(document);

        assertThat(parsed.title()).isEqualTo("Adaptive filtering methods for biomedical signal analysis");
        assertThat(parsed.doi()).isEqualTo("10.99999/fixture.42");
        assertThat(parsed.datePublished()).isEqualTo("2026/02/15");
        assertThat(parsed.dateSubmitted()).isEqualTo("2025-11-20");
        assertThat(parsed.dateAccepted()).isEqualTo("2026-01-18");
        assertThat(parsed.pages()).isEqualTo("55-71");
        assertThat(parsed.references()).hasSize(4);

        List<String> labelledAuthors = List.of(
                "Layla Ibrahim|University of Basrah, Iraq",
                "Hassan Jassim|University of Technology, Iraq",
                "Nour Salman|Mutah University, Jordan");
        List<String> extractedAuthors = parsed.authors().stream()
                .map(a -> a.name() + "|" + a.affiliation())
                .toList();
        long truePositives = extractedAuthors.stream().filter(labelledAuthors::contains).count();
        double precision = extractedAuthors.isEmpty() ? 0
                : (double) truePositives / extractedAuthors.size();

        assertThat(precision)
                .as("authorship extraction precision (NFR-AI-1 requires >= 0.95); extracted=%s",
                        extractedAuthors)
                .isGreaterThanOrEqualTo(0.95);
        assertThat(extractedAuthors).hasSize(3);
    }

    private Document load(String resource) throws IOException {
        try (InputStream in = getClass().getResourceAsStream(resource)) {
            assertThat(in).as("fixture %s", resource).isNotNull();
            return Jsoup.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8),
                    "https://journal.example/index.php/tj");
        }
    }
}
