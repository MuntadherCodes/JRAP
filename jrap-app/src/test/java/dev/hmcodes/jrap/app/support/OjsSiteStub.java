package dev.hmcodes.jrap.app.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A miniature OJS journal site + its scholarly-API records, all on one server:
 * home (OJS generator meta), about/editorial/ethics pages, /issue/archive with two
 * issues and three articles, one PDF galley, a robots-disallowed /private/ page linked
 * from home, an OAI-PMH endpoint that deliberately lists MORE records (10) than the
 * site publishes (3) to trip the FR-CRWL-2 cross-check, and OpenAlex/Crossref/DOAJ
 * endpoints whose homepage points back at this server so the crawler targets it.
 */
public final class OjsSiteStub {

    public static final String ISSN = "0378-5955";
    public static final String TITLE = "Stub Journal of Clinical Medicine";
    public static final String PDF_SENTENCE = "Randomized trial of stub interventions";
    public static final String OJS_GENERATOR = "Open Journal Systems 3.3.0.8";

    private final HttpServer server;
    private final Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();
    private final byte[] pdfBytes;
    private final String issn;
    private final String title;

    public OjsSiteStub() {
        this(ISSN, TITLE);
    }

    /**
     * A stub with its own ISSN/title. The api_record cache is global across test classes
     * in the shared database, so any class whose source stubs must answer differently
     * (e.g. the AC-7 degradation test) MUST use a distinct ISSN or it will read the
     * cached responses recorded from another class's stub.
     */
    public OjsSiteStub(String issn, String title) {
        this.issn = issn;
        this.title = title;
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        pdfBytes = buildPdf();
        server.createContext("/", this::handle);
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public int hitsFor(String path) {
        AtomicInteger counter = hits.get(path);
        return counter == null ? 0 : counter.get();
    }

    public void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String query = exchange.getRequestURI().getQuery();
        hits.computeIfAbsent(path, key -> new AtomicInteger()).incrementAndGet();

        switch (path) {
            case "/robots.txt" -> respond(exchange, 200, "text/plain",
                    "User-agent: *\nDisallow: /private/\n".getBytes(StandardCharsets.UTF_8));
            case "/", "" -> html(exchange, """
                    <head><title>%s</title><meta name="generator" content="%s"></head>
                    <body><h1>%s</h1><p>ISSN: %s</p>
                    <p>Indexed in Scopus and other databases.</p>
                    <a href="/about">About</a>
                    <a href="/about/editorialTeam">Editorial Team</a>
                    <a href="/ethics">Publication Ethics</a>
                    <a href="/issue/archive">Archives</a>
                    <a href="/announcement">Announcements</a>
                    <a href="/private/secret">Internal</a>
                    </body>""".formatted(title, OJS_GENERATOR, title, issn));
            case "/about" -> html(exchange, """
                    <head><title>About the Journal</title></head><body>About.
                    <p>A multidisciplinary scope covering medicine, engineering, physics,
                    chemistry, biology, economics, law, and education.</p>
                    <a href='/about/submissions'>Submissions</a></body>""");
            case "/about/editorialTeam" -> html(exchange, """
                    <head><title>Editorial Team</title></head><body>
                    <h3>Editor-in-Chief</h3>
                    <p><strong>Prof. Ali Hassan</strong>, University of Baghdad, Iraq
                       <a href="https://orcid.org/0000-0001-2345-6789">ORCID</a></p>
                    <h3>Associate Editors</h3>
                    <p><strong>Dr. Sara Ahmed</strong>, University of Jordan, Jordan</p>
                    <p><strong>Dr. Omar Khalid</strong>, Cairo University, Egypt</p>
                    <h3>Editorial Board Members</h3>
                    <p><strong>Prof. Fatima Noor</strong>, King Saud University, Saudi Arabia</p>
                    <p><strong>Dr. John Smith</strong>, University of Manchester, United Kingdom</p>
                    </body>""");
            case "/about/submissions" -> html(exchange, "<head><title>Submissions</title></head><body>Author guidelines here</body>");
            case "/ethics" -> html(exchange, "<head><title>Publication Ethics</title></head><body>COPE-aligned malpractice statement</body>");
            case "/announcement" -> html(exchange, """
                    <head><title>Announcements</title></head><body>
                    <p>Notice to authors: submissions must cite at least three papers previously
                       published in this journal to be considered.</p></body>""");
            case "/issue/archive" -> html(exchange, """
                    <head><title>Archives</title></head><body>
                    <a href="/issue/view/1">Vol 1</a> <a href="/issue/view/2">Vol 2</a></body>""");
            case "/issue/view/1" -> html(exchange, """
                    <head><title>Vol 1</title></head><body>
                    <a href="/article/view/101">Article 101</a> <a href="/article/view/102">Article 102</a></body>""");
            case "/issue/view/2" -> html(exchange, """
                    <head><title>Vol 2</title></head><body>
                    <a href="/article/view/201">Article 201</a></body>""");
            case "/article/view/101" -> html(exchange, articleHtml(101,
                    "Machine learning for stub diagnostics", "10.99999/stub.101"));
            case "/article/view/102" -> html(exchange, articleHtml(102,
                    "Deep survey of stub networks", "10.99999/stub.102"));
            case "/article/view/201" -> html(exchange, articleHtml(201,
                    "Stub optimisation in clinical settings", "10.99999/stub.201"));
            case "/article/download/101/1" -> respond(exchange, 200, "application/pdf", pdfBytes);
            case "/private/secret" -> html(exchange, "<head><title>Secret</title></head><body>should never be fetched</body>");
            case "/oai" -> respond(exchange, 200, "text/xml",
                    oaiXml(query).getBytes(StandardCharsets.UTF_8));
            default -> {
                if (path.startsWith("/works/")) {
                    String rest = path.substring("/works/".length());
                    boolean openAlexStyle = rest.contains("doi.org");
                    String doi = openAlexStyle
                            ? rest.substring(rest.indexOf("doi.org/") + "doi.org/".length())
                            : rest;
                    respond(exchange, 200, "application/json",
                            (openAlexStyle ? openAlexWorkJson(doi) : crossrefWorkJson(doi))
                                    .getBytes(StandardCharsets.UTF_8));
                } else if (path.startsWith("/sources/issn:")) {
                    respond(exchange, 200, "application/json", openAlexJson().getBytes(StandardCharsets.UTF_8));
                } else if (path.startsWith("/journals/")) {
                    respond(exchange, 200, "application/json", crossrefJson().getBytes(StandardCharsets.UTF_8));
                } else if (path.startsWith("/api/search/journals/")) {
                    respond(exchange, 200, "application/json",
                            "{\"total\":0,\"results\":[]}".getBytes(StandardCharsets.UTF_8));
                } else if (path.startsWith("/resource/ISSN/")) {
                    respond(exchange, 403, "text/html", "denied".getBytes(StandardCharsets.UTF_8));
                } else {
                    respond(exchange, 404, "text/html", "not found".getBytes(StandardCharsets.UTF_8));
                }
            }
        }
    }

    private String openAlexJson() {
        // Seeded anomalies: works 5->12 (spike) ->5 (collapse); citations 40->90 (surge)
        // ->10->8 (collapse) => RF-01, RF-02 and a COLLAPSING trend with standing floored.
        // Years are RELATIVE to the wall clock so the gap-year window (engine uses
        // Clock.now) sees the same pattern whatever year CI runs in.
        int y = java.time.Year.now().getValue();
        return """
                {"id":"https://openalex.org/S42109","display_name":"%s","issn_l":"%s",
                 "issn":["%s"],"host_organization_name":"Stub University Press",
                 "country_code":"IQ","homepage_url":"%s/","works_count":25,"cited_by_count":148,
                 "counts_by_year":[
                   {"year":%d,"works_count":3,"cited_by_count":8},
                   {"year":%d,"works_count":5,"cited_by_count":10},
                   {"year":%d,"works_count":12,"cited_by_count":90},
                   {"year":%d,"works_count":5,"cited_by_count":40}]}
                """.formatted(title, issn, issn, baseUrl(), y - 1, y - 2, y - 3, y - 4);
    }

    private String crossrefJson() {
        return """
                {"status":"ok","message":{"title":"%s","publisher":"Stub University Press",
                 "ISSN":["%s"],"issn-type":[{"value":"%s","type":"print"}],
                 "counts":{"total-dois":3}}}
                """.formatted(title, issn, issn);
    }

    private String oaiXml(String query) {
        if (query == null || !query.contains("verb=ListRecords")) {
            return "<OAI-PMH><error code=\"badVerb\"/></OAI-PMH>";
        }
        StringBuilder recordsXml = new StringBuilder();
        for (int i = 1; i <= 10; i++) {
            recordsXml.append("""
                    <record><header><identifier>oai:stub:article/%d</identifier>
                    <datestamp>2026-0%d-01</datestamp></header>
                    <metadata><oai_dc:dc xmlns:oai_dc="http://www.openarchives.org/OAI/2.0/oai_dc/"
                    xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>Stub article %d</dc:title></oai_dc:dc></metadata></record>
                    """.formatted(i, (i % 9) + 1, i));
        }
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <OAI-PMH xmlns="http://www.openarchives.org/OAI/2.0/">
                <responseDate>2026-08-28T00:00:00Z</responseDate>
                <ListRecords>%s</ListRecords></OAI-PMH>
                """.formatted(recordsXml);
    }

    private String articleHtml(int id, String title, String doi) {
        return """
                <head>
                  <title>%s</title>
                  <meta name="citation_title" content="%s">
                  <meta name="citation_author" content="Ali Hassan">
                  <meta name="citation_author_institution" content="University of Baghdad, Iraq">
                  <meta name="citation_author" content="Sara Ahmed">
                  <meta name="citation_author_institution" content="University of Jordan, Jordan">
                  <meta name="citation_doi" content="%s">
                  <meta name="citation_publication_date" content="2026/03/01">
                  <meta name="citation_firstpage" content="1">
                  <meta name="citation_lastpage" content="12">
                  <meta name="citation_keywords" content="stub; testing; extraction">
                  <meta name="DC.Description" content="This study evaluates the performance of stub systems in a controlled environment and reports the results of the evaluation across multiple settings.">
                </head>
                <body><h1>%s</h1>
                <p>Received: 2026-01-05 | Accepted: 2026-02-10 | Published: 2026-03-01</p>
                <p>DOI: https://doi.org/%s</p>
                %s
                <div class="item references"><h3>References</h3>
                  <p>Smith J, Brown K. Foundations of stub science. Journal of Stubs. 2024;12(3):45-58.</p>
                  <p>Ahmed S. Advanced stub methodology and its applications. Stub Review. 2025;8(1):12-29.</p>
                  <p>Hassan A, Noor F. Clinical stub interventions: a systematic review. 2023;5(2):101-118.</p>
                </div>
                </body>""".formatted(title, title, doi, title, doi,
                id == 101 ? "<a href=\"/article/download/101/1\">PDF</a>" : "");
    }

    private String crossrefWorkJson(String doi) {
        // stub.102's Crossref record disagrees with the site title (seeded FR-EXT-5 mismatch).
        String title = doi.endsWith("stub.102")
                ? "Completely different archival title"
                : switch (doi.substring(doi.length() - 3)) {
                    case "101" -> "Machine learning for stub diagnostics";
                    case "201" -> "Stub optimisation in clinical settings";
                    default -> "Unknown";
                };
        return """
                {"status":"ok","message":{"title":["%s"],
                 "author":[{"given":"Ali","family":"Hassan"},{"given":"Sara","family":"Ahmed"}],
                 "issued":{"date-parts":[[2026,3,1]]}}}
                """.formatted(title);
    }

    private String openAlexWorkJson(String doi) {
        String title = switch (doi.substring(doi.length() - 3)) {
            case "101" -> "Machine learning for stub diagnostics";
            case "102" -> "Deep survey of stub networks";
            case "201" -> "Stub optimisation in clinical settings";
            default -> "Unknown";
        };
        return """
                {"display_name":"%s","publication_year":2026,
                 "authorships":[{"author":{"display_name":"Ali Hassan"}},
                                {"author":{"display_name":"Sara Ahmed"}}]}
                """.formatted(title);
    }

    private byte[] buildPdf() {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(72, 700);
                content.showText(PDF_SENTENCE);
                content.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void html(HttpExchange exchange, String body) throws IOException {
        respond(exchange, 200, "text/html", ("<!DOCTYPE html><html>" + body + "</html>")
                .getBytes(StandardCharsets.UTF_8));
    }

    private void respond(HttpExchange exchange, int status, String contentType, byte[] bytes)
            throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType + "; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
