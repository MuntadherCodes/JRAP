package dev.hmcodes.jrap.crawl.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PageClassifierTest {

    private final PageClassifier classifier = new PageClassifier();
    private static final String HOME = "https://j.example/index.php/wjcm";

    @Test
    void classifiesOjsPatterns() {
        assertThat(classifier.classify(HOME + "/issue/archive", "text/html", "Archives", HOME))
                .isEqualTo("archive");
        assertThat(classifier.classify(HOME + "/issue/view/12", "text/html", "Vol 3", HOME))
                .isEqualTo("issue");
        assertThat(classifier.classify(HOME + "/article/view/381", "text/html", "Some article", HOME))
                .isEqualTo("article-landing");
        assertThat(classifier.classify(HOME + "/article/download/381/210", "application/pdf", null, HOME))
                .isEqualTo("article-pdf");
    }

    @Test
    void classifiesPolicyPagesBySlugOrTitleIncludingMisspeltSlugs() {
        assertThat(classifier.classify(HOME + "/about/editorialTeam", "text/html", null, HOME))
                .isEqualTo("editorial-team");
        assertThat(classifier.classify(HOME + "/ethiccs", "text/html", "Publication Ethics", HOME))
                .isEqualTo("ethics"); // misspelt slug, title carries it (FR-CRWL-1)
        assertThat(classifier.classify(HOME + "/pageX", "text/html", "Peer-Review Process", HOME))
                .isEqualTo("peer-review-policy");
        assertThat(classifier.classify(HOME, "text/html", "Journal home", HOME)).isEqualTo("home");
        assertThat(classifier.classify(HOME + "/some-random-page", "text/html", "Misc", HOME))
                .isEqualTo("other");
    }

    @Test
    void urlNormalisationStripsFragmentsAndSessionIds() {
        assertThat(CrawlService.normaliseUrl("https://j.example/a;jsessionid=ABC?x=1#frag"))
                .isEqualTo("https://j.example/a?x=1");
        assertThat(CrawlService.normaliseUrl("ftp://j.example/a")).isNull();
        assertThat(CrawlService.normaliseUrl(null)).isNull();
    }
}
