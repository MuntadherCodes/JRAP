package dev.hmcodes.jrap.app.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-process stand-in for OpenAlex, Crossref, DOAJ, the ISSN Portal and a journal
 * homepage, with seeded identity inconsistencies:
 * DOAJ states the print/online ISSNs SWAPPED relative to Crossref, and a different
 * publisher; the ISSN Portal answers 403 (blocked, as it commonly is for robots).
 */
public final class ScholarSourceStub {

    public static final String ISSN_PRINT = "2708-9134";
    public static final String ISSN_ONLINE = "2708-9126";
    public static final String TITLE = "World Journal of Clinical Medicine";
    public static final String PUBLISHER = "University of Baghdad Press";
    public static final String DOAJ_PUBLISHER = "Baghdad Medical Society";
    public static final String OJS_GENERATOR = "Open Journal Systems 3.3.0.8";

    private final HttpServer server;
    private final Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();

    public ScholarSourceStub() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/", this::handle);
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public int hitsFor(String pathPrefix) {
        return hits.entrySet().stream()
                .filter(e -> e.getKey().startsWith(pathPrefix))
                .mapToInt(e -> e.getValue().get())
                .sum();
    }

    public void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        hits.computeIfAbsent(path, key -> new AtomicInteger()).incrementAndGet();

        if (path.equals("/robots.txt")) {
            respond(exchange, 200, "text/plain", "User-agent: *\nDisallow: /private/\n");
        } else if (path.startsWith("/sources/issn:")) {
            String issn = path.substring("/sources/issn:".length());
            if (issn.equals(ISSN_PRINT) || issn.equals(ISSN_ONLINE)) {
                respond(exchange, 200, "application/json", openAlexJson());
            } else {
                respond(exchange, 404, "application/json", "{\"error\":\"not found\"}");
            }
        } else if (path.startsWith("/journals/")) {
            String issn = path.substring("/journals/".length());
            if (issn.equals(ISSN_PRINT) || issn.equals(ISSN_ONLINE)) {
                respond(exchange, 200, "application/json", crossrefJson());
            } else {
                respond(exchange, 404, "application/json", "{\"status\":\"error\"}");
            }
        } else if (path.startsWith("/api/search/journals/")) {
            String query = path.substring("/api/search/journals/".length());
            if (query.contains(ISSN_PRINT) || query.contains(ISSN_ONLINE)) {
                respond(exchange, 200, "application/json", doajJson());
            } else {
                respond(exchange, 200, "application/json", "{\"total\":0,\"results\":[]}");
            }
        } else if (path.startsWith("/resource/ISSN/")) {
            respond(exchange, 403, "text/html", "<html>Access denied</html>");
        } else if (path.equals("/journal-home")) {
            respond(exchange, 200, "text/html", homepageHtml());
        } else {
            respond(exchange, 404, "text/plain", "not found");
        }
    }

    private String openAlexJson() {
        return """
                {
                  "id": "https://openalex.org/S4210999999",
                  "display_name": "%s",
                  "issn_l": "%s",
                  "issn": ["%s", "%s"],
                  "host_organization_name": "%s",
                  "country_code": "IQ",
                  "homepage_url": "%s/journal-home",
                  "works_count": 412,
                  "cited_by_count": 655,
                  "summary_stats": {"2yr_mean_citedness": 0.41, "h_index": 9}
                }
                """.formatted(TITLE, ISSN_PRINT, ISSN_PRINT, ISSN_ONLINE, PUBLISHER, baseUrl());
    }

    private String crossrefJson() {
        return """
                {
                  "status": "ok",
                  "message": {
                    "title": "%s",
                    "publisher": "%s",
                    "ISSN": ["%s", "%s"],
                    "issn-type": [
                      {"value": "%s", "type": "print"},
                      {"value": "%s", "type": "electronic"}
                    ],
                    "counts": {"total-dois": 412},
                    "breakdowns": {"dois-by-issued-year": [[2024, 96], [2025, 210], [2026, 106]]}
                  }
                }
                """.formatted(TITLE, PUBLISHER, ISSN_PRINT, ISSN_ONLINE, ISSN_PRINT, ISSN_ONLINE);
    }

    private String doajJson() {
        // NOTE the seeded inconsistency: pissn/eissn are SWAPPED relative to Crossref,
        // and the publisher differs.
        return """
                {
                  "total": 1,
                  "results": [{
                    "id": "doaj-abc123",
                    "bibjson": {
                      "title": "%s",
                      "publisher": {"name": "%s", "country": "IQ"},
                      "pissn": "%s",
                      "eissn": "%s",
                      "apc": {"has_apc": true},
                      "preservation": {"has_preservation": false},
                      "ref": {"journal": "%s/journal-home"}
                    }
                  }]
                }
                """.formatted(TITLE, DOAJ_PUBLISHER, ISSN_ONLINE, ISSN_PRINT, baseUrl());
    }

    private String homepageHtml() {
        return """
                <!DOCTYPE html>
                <html>
                <head>
                  <title>%s</title>
                  <meta name="generator" content="%s">
                </head>
                <body>
                  <h1>%s</h1>
                  <p>ISSN: %s (print), ISSN: %s (online)</p>
                </body>
                </html>
                """.formatted(TITLE, OJS_GENERATOR, TITLE, ISSN_PRINT, ISSN_ONLINE);
    }

    private void respond(HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType + "; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
