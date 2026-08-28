package dev.hmcodes.jrap.reporting.export;

import dev.hmcodes.jrap.reporting.model.ReportContent.Exclusion;
import dev.hmcodes.jrap.reporting.model.ReportContent.RoadmapAction;
import dev.hmcodes.jrap.reporting.model.ReportContent.Section;
import dev.hmcodes.jrap.reporting.model.ReportContent.Sentence;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * FR-RPT-5 DOCX export, dependency-free: a .docx is a zip of WordprocessingML parts,
 * built here by hand. Citations render as real Word footnotes (FR-RPT-3): each cited
 * annex entry becomes one footnote, referenced with a superscript mark after the
 * sentence. Pure function of the export model.
 */
public final class DocxExporter {

    private DocxExporter() {}

    public static byte[] render(ExportModel model) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
                put(zip, "[Content_Types].xml", contentTypes());
                put(zip, "_rels/.rels", packageRels());
                put(zip, "word/_rels/document.xml.rels", documentRels());
                put(zip, "word/footnotes.xml", footnotes(model));
                put(zip, "word/document.xml", document(model));
            }
            return bytes.toByteArray();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("DOCX assembly failed", e);
        }
    }

    private static void put(ZipOutputStream zip, String name, String content)
            throws java.io.IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String contentTypes() {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                <Default Extension="xml" ContentType="application/xml"/>
                <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
                <Override PartName="/word/footnotes.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.footnotes+xml"/>
                </Types>""";
    }

    private static String packageRels() {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
                </Relationships>""";
    }

    private static String documentRels() {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/footnotes" Target="footnotes.xml"/>
                </Relationships>""";
    }

    /** Footnote ids: 0 separator, 1 continuation separator, then annex number + 1. */
    private static String footnotes(ExportModel model) {
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
                .append("<w:footnotes xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">")
                .append("<w:footnote w:type=\"separator\" w:id=\"0\"><w:p><w:r><w:separator/></w:r></w:p></w:footnote>")
                .append("<w:footnote w:type=\"continuationSeparator\" w:id=\"1\"><w:p><w:r>")
                .append("<w:continuationSeparator/></w:r></w:p></w:footnote>");
        for (ExportModel.CitedEvidence evidence : model.evidence()) {
            xml.append("<w:footnote w:id=\"").append(evidence.number() + 1).append("\"><w:p><w:r>")
                    .append("<w:rPr><w:vertAlign w:val=\"superscript\"/></w:rPr><w:footnoteRef/></w:r>")
                    .append(run(" " + evidence.source() + " (" + evidence.type() + ", retrieved "
                            + evidence.retrievedAt() + "): "
                            + (evidence.excerpt() == null ? "" : evidence.excerpt()), false))
                    .append("</w:p></w:footnote>");
        }
        xml.append("</w:footnotes>");
        return xml.toString();
    }

    private static String document(ExportModel model) {
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
                .append("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">")
                .append("<w:body>");

        if (model.watermark() != null) {
            xml.append(paragraph(model.watermark(), true, 28));
        }
        xml.append(paragraph("Journal Readiness Audit Report", true, 36));
        xml.append(paragraph(model.journalTitle() + " — prepared for " + model.organisationName()
                + " — verdict: " + model.verdict().replace('_', ' ') + " — report v" + model.version()
                + " — rubric v" + model.rubricVersion(), false, 22));

        for (Section section : model.sections()) {
            xml.append(paragraph(section.title(), true, 28));
            for (Sentence sentence : section.sentences()) {
                StringBuilder p = new StringBuilder("<w:p><w:r><w:t xml:space=\"preserve\">")
                        .append(esc(sentence.text())).append(" </w:t></w:r>");
                for (int number : model.citationNumbers().getOrDefault(sentence.id(), List.<Integer>of())) {
                    p.append("<w:r><w:rPr><w:vertAlign w:val=\"superscript\"/></w:rPr>")
                            .append("<w:footnoteReference w:id=\"").append(number + 1).append("\"/></w:r>");
                }
                p.append("</w:p>");
                xml.append(p);
            }
        }

        xml.append(paragraph("Remediation roadmap", true, 28));
        for (RoadmapAction action : model.roadmap()) {
            xml.append(paragraph("[" + phaseLabel(action.phase()) + ", "
                    + action.tag().replace('_', '-').toLowerCase() + "] " + action.title() + " — "
                    + action.description() + " Completion: " + action.completionCriterion(), false, 22));
        }

        if (!model.exclusions().isEmpty()) {
            xml.append(paragraph("Annex: analyst exclusions", true, 28));
            for (Exclusion exclusion : model.exclusions()) {
                xml.append(paragraph(exclusion.code() + " — " + exclusion.title() + " — excluded: "
                        + exclusion.reason(), false, 22));
            }
        }

        xml.append(paragraph(model.stamp(), false, 18));
        xml.append("<w:sectPr><w:footnotePr><w:footnote w:id=\"0\"/><w:footnote w:id=\"1\"/>")
                .append("</w:footnotePr></w:sectPr>");
        xml.append("</w:body></w:document>");
        return xml.toString();
    }

    private static String paragraph(String text, boolean bold, int halfPoints) {
        return "<w:p><w:pPr><w:spacing w:after=\"120\"/></w:pPr><w:r><w:rPr>"
                + (bold ? "<w:b/>" : "")
                + "<w:sz w:val=\"" + halfPoints + "\"/></w:rPr><w:t xml:space=\"preserve\">"
                + esc(text) + "</w:t></w:r></w:p>";
    }

    private static String run(String text, boolean bold) {
        return "<w:r><w:rPr>" + (bold ? "<w:b/>" : "") + "</w:rPr><w:t xml:space=\"preserve\">"
                + esc(text) + "</w:t></w:r>";
    }

    private static String phaseLabel(String phase) {
        return switch (phase) {
            case "P0_3" -> "0-3 months";
            case "P3_6" -> "3-6 months";
            default -> "6-12 months";
        };
    }

    private static String esc(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                default -> {
                    if (c >= 0x20 || c == '\t') {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
