package dev.hmcodes.jrap.reporting.export;

import dev.hmcodes.jrap.reporting.model.ReportContent.Exclusion;
import dev.hmcodes.jrap.reporting.model.ReportContent.RoadmapAction;
import dev.hmcodes.jrap.reporting.model.ReportContent.Section;
import dev.hmcodes.jrap.reporting.model.ReportContent.Sentence;

import java.util.List;

/**
 * FR-RPT-5 HTML export: one self-contained page, superscript citation links with
 * hover popovers showing the evidence excerpt (FR-RPT-3), roadmap table, exclusions
 * and evidence annexes, hash stamp in the footer. Pure function of the export model.
 */
public final class HtmlExporter {

    private HtmlExporter() {}

    public static String render(ExportModel model) {
        StringBuilder html = new StringBuilder();
        html.append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">")
                .append("<title>").append(esc(model.journalTitle())).append(" — Readiness Report</title>")
                .append("<style>")
                .append("body{font-family:Georgia,serif;max-width:60em;margin:2em auto;padding:0 1em;")
                .append("color:#1c1e26;line-height:1.6}")
                .append("h1{font-size:1.6em}h2{font-size:1.2em;margin-top:1.6em;border-bottom:1px solid #ccc}")
                .append(".watermark{color:#b3261e;border:2px solid #b3261e;padding:.4em .8em;")
                .append("display:inline-block;font-weight:bold}")
                .append(".meta{color:#555;font-size:.9em}")
                .append("sup.cite{font-size:.7em}sup.cite a{text-decoration:none;color:#4650dd}")
                .append(".cite-wrap{position:relative}")
                .append(".cite-wrap .pop{display:none;position:absolute;left:0;bottom:1.4em;width:28em;")
                .append("background:#fff;border:1px solid #999;box-shadow:0 2px 8px rgba(0,0,0,.2);")
                .append("padding:.6em;font-size:.85em;z-index:9}")
                .append(".cite-wrap:hover .pop{display:block}")
                .append("table{border-collapse:collapse;width:100%}td,th{border:1px solid #ccc;")
                .append("padding:.3em .5em;text-align:start;vertical-align:top}")
                .append(".tag-MUST_FIX{color:#b3261e;font-weight:bold}.tag-STRENGTHENS{color:#1b7f4d}")
                .append("footer{margin-top:2em;color:#555;font-size:.85em;border-top:1px solid #ccc}")
                .append("</style></head><body>");

        if (model.watermark() != null) {
            html.append("<p class=\"watermark\">").append(esc(model.watermark())).append("</p>");
        }
        html.append("<h1>Journal Readiness Audit Report</h1>")
                .append("<p class=\"meta\">").append(esc(model.journalTitle()))
                .append(" · prepared for ").append(esc(model.organisationName()))
                .append(" · verdict: <strong>").append(esc(model.verdict().replace('_', ' ')))
                .append("</strong> · report v").append(model.version())
                .append(" · rubric v").append(esc(model.rubricVersion())).append("</p>");

        for (Section section : model.sections()) {
            html.append("<h2 id=\"").append(esc(section.id())).append("\">")
                    .append(esc(section.title())).append("</h2><p>");
            for (Sentence sentence : section.sentences()) {
                html.append("<span dir=\"auto\">").append(esc(sentence.text())).append("</span>");
                List<Integer> cites = model.citationNumbers().getOrDefault(sentence.id(), List.of());
                for (int number : cites) {
                    ExportModel.CitedEvidence evidence = model.evidence().get(number - 1);
                    html.append("<span class=\"cite-wrap\"><sup class=\"cite\">")
                            .append("<a href=\"#ev-").append(number).append("\">[").append(number)
                            .append("]</a></sup><span class=\"pop\"><strong>")
                            .append(esc(evidence.source())).append("</strong> · ")
                            .append(esc(String.valueOf(evidence.retrievedAt()))).append("<br>")
                            .append(esc(evidence.excerpt() == null ? "" : evidence.excerpt()))
                            .append("</span></span>");
                }
                html.append(" ");
            }
            html.append("</p>");
        }

        // Roadmap (FR-RPT-6)
        html.append("<h2>Remediation roadmap</h2>");
        for (String phase : List.of("P0_3", "P3_6", "P6_12")) {
            List<RoadmapAction> actions = model.roadmap().stream()
                    .filter(a -> a.phase().equals(phase)).toList();
            if (actions.isEmpty()) {
                continue;
            }
            html.append("<h3>").append(phaseLabel(phase)).append("</h3><table><tr><th>Action</th>")
                    .append("<th>Tag</th><th>Completion criterion</th></tr>");
            for (RoadmapAction action : actions) {
                html.append("<tr><td><strong>").append(esc(action.title())).append("</strong><br>")
                        .append(esc(action.description())).append("</td><td class=\"tag-")
                        .append(esc(action.tag())).append("\">")
                        .append(esc(action.tag().replace('_', '-').toLowerCase())).append("</td><td>")
                        .append(esc(action.completionCriterion())).append("</td></tr>");
            }
            html.append("</table>");
        }

        // Exclusions annex (FR-REV-4)
        if (!model.exclusions().isEmpty()) {
            html.append("<h2>Annex: analyst exclusions</h2><table>")
                    .append("<tr><th>Code</th><th>Finding</th><th>Reason for exclusion</th></tr>");
            for (Exclusion exclusion : model.exclusions()) {
                html.append("<tr><td>").append(esc(exclusion.code())).append("</td><td>")
                        .append(esc(exclusion.title())).append("</td><td>")
                        .append(esc(exclusion.reason())).append("</td></tr>");
            }
            html.append("</table>");
        }

        // Evidence annex (FR-RPT-1, CON-5)
        html.append("<h2>Annex: evidence</h2><table><tr><th>#</th><th>Type</th><th>Source</th>")
                .append("<th>Retrieved</th><th>Excerpt</th></tr>");
        for (ExportModel.CitedEvidence evidence : model.evidence()) {
            html.append("<tr id=\"ev-").append(evidence.number()).append("\"><td>")
                    .append(evidence.number()).append("</td><td>").append(esc(evidence.type()))
                    .append("</td><td>").append(esc(evidence.source())).append("</td><td>")
                    .append(esc(String.valueOf(evidence.retrievedAt()))).append("</td><td dir=\"auto\">")
                    .append(esc(evidence.excerpt() == null ? "" : evidence.excerpt()))
                    .append("</td></tr>");
        }
        html.append("</table>");

        html.append("<footer><p>").append(esc(model.stamp())).append("</p></footer>");
        html.append("</body></html>");
        return html.toString();
    }

    private static String phaseLabel(String phase) {
        return switch (phase) {
            case "P0_3" -> "0–3 months";
            case "P3_6" -> "3–6 months";
            default -> "6–12 months";
        };
    }

    private static String esc(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
