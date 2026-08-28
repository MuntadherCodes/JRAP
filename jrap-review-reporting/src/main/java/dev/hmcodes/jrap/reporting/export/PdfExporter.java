package dev.hmcodes.jrap.reporting.export;

import dev.hmcodes.jrap.reporting.model.ReportContent.Exclusion;
import dev.hmcodes.jrap.reporting.model.ReportContent.RoadmapAction;
import dev.hmcodes.jrap.reporting.model.ReportContent.Section;
import dev.hmcodes.jrap.reporting.model.ReportContent.Sentence;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * FR-RPT-5 PDF export, dependency-free: a minimal hand-assembled PDF (Helvetica,
 * WinAnsi) with citations as bracketed superscript-style markers resolved in a
 * numbered evidence-citations section (the FR-RPT-3 footnote channel for PDF).
 * Beta limitation: non-Latin-1 characters (e.g. Arabic) are transliterated to '?';
 * the HTML and DOCX exports carry full Unicode.
 */
public final class PdfExporter {

    private static final int PAGE_WIDTH = 595;   // A4 portrait, points
    private static final int PAGE_HEIGHT = 842;
    private static final int MARGIN = 50;
    private static final int LEADING = 13;
    private static final int WRAP = 96;

    private record Line(String text, boolean bold) {}

    private PdfExporter() {}

    public static byte[] render(ExportModel model) {
        List<Line> lines = layout(model);

        // Paginate.
        int linesPerPage = (PAGE_HEIGHT - 2 * MARGIN) / LEADING;
        List<List<Line>> pages = new ArrayList<>();
        for (int i = 0; i < lines.size(); i += linesPerPage) {
            pages.add(lines.subList(i, Math.min(lines.size(), i + linesPerPage)));
        }
        if (pages.isEmpty()) {
            pages.add(List.of(new Line("(empty report)", false)));
        }

        // Objects: 1 catalog, 2 pages, 3 F1, 4 F2, then per page: page obj + content obj.
        List<byte[]> objects = new ArrayList<>();
        int pageCount = pages.size();
        StringBuilder kids = new StringBuilder();
        for (int i = 0; i < pageCount; i++) {
            kids.append(5 + i * 2).append(" 0 R ");
        }
        objects.add(obj(1, "<< /Type /Catalog /Pages 2 0 R >>"));
        objects.add(obj(2, "<< /Type /Pages /Kids [" + kids.toString().trim() + "] /Count "
                + pageCount + " >>"));
        objects.add(obj(3, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica"
                + " /Encoding /WinAnsiEncoding >>"));
        objects.add(obj(4, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold"
                + " /Encoding /WinAnsiEncoding >>"));
        for (int i = 0; i < pageCount; i++) {
            int pageId = 5 + i * 2;
            int contentId = pageId + 1;
            objects.add(obj(pageId, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " + PAGE_WIDTH + " "
                    + PAGE_HEIGHT + "] /Resources << /Font << /F1 3 0 R /F2 4 0 R >> >> /Contents "
                    + contentId + " 0 R >>"));
            byte[] stream = content(pages.get(i));
            objects.add(streamObj(contentId, stream));
        }

        return assemble(objects);
    }

    // ------------------------------------------------------------------ layout

    private static List<Line> layout(ExportModel model) {
        List<Line> lines = new ArrayList<>();
        if (model.watermark() != null) {
            lines.add(new Line(model.watermark(), true));
            lines.add(new Line("", false));
        }
        lines.add(new Line("Journal Readiness Audit Report", true));
        wrap(lines, model.journalTitle() + " - prepared for " + model.organisationName()
                + " - verdict: " + model.verdict().replace('_', ' ') + " - report v"
                + model.version() + " - rubric v" + model.rubricVersion(), false);
        lines.add(new Line("", false));

        for (Section section : model.sections()) {
            lines.add(new Line(section.title(), true));
            for (Sentence sentence : section.sentences()) {
                StringBuilder text = new StringBuilder(sentence.text());
                for (int number : model.citationNumbers().getOrDefault(sentence.id(), List.<Integer>of())) {
                    text.append(" [").append(number).append("]");
                }
                wrap(lines, text.toString(), false);
            }
            lines.add(new Line("", false));
        }

        lines.add(new Line("Remediation roadmap", true));
        for (RoadmapAction action : model.roadmap()) {
            wrap(lines, "[" + phaseLabel(action.phase()) + ", "
                    + action.tag().replace('_', '-').toLowerCase() + "] " + action.title() + " - "
                    + action.description() + " Completion: " + action.completionCriterion(), false);
        }
        lines.add(new Line("", false));

        if (!model.exclusions().isEmpty()) {
            lines.add(new Line("Annex: analyst exclusions", true));
            for (Exclusion exclusion : model.exclusions()) {
                wrap(lines, exclusion.code() + " - " + exclusion.title() + " - excluded: "
                        + exclusion.reason(), false);
            }
            lines.add(new Line("", false));
        }

        lines.add(new Line("Evidence citations", true));
        for (ExportModel.CitedEvidence evidence : model.evidence()) {
            wrap(lines, "[" + evidence.number() + "] " + evidence.source() + " (" + evidence.type()
                    + ", retrieved " + evidence.retrievedAt() + "): "
                    + (evidence.excerpt() == null ? "" : evidence.excerpt()), false);
        }
        lines.add(new Line("", false));
        wrap(lines, model.stamp(), false);
        return lines;
    }

    private static void wrap(List<Line> lines, String text, boolean bold) {
        String remaining = text == null ? "" : text;
        while (remaining.length() > WRAP) {
            int cut = remaining.lastIndexOf(' ', WRAP);
            if (cut <= 0) {
                cut = WRAP;
            }
            lines.add(new Line(remaining.substring(0, cut), bold));
            remaining = remaining.substring(cut).stripLeading();
        }
        lines.add(new Line(remaining, bold));
    }

    private static String phaseLabel(String phase) {
        return switch (phase) {
            case "P0_3" -> "0-3 months";
            case "P3_6" -> "3-6 months";
            default -> "6-12 months";
        };
    }

    // ------------------------------------------------------------------ PDF assembly

    private static byte[] content(List<Line> lines) {
        StringBuilder stream = new StringBuilder();
        stream.append("BT\n/F1 10 Tf\n").append(LEADING).append(" TL\n")
                .append(MARGIN).append(' ').append(PAGE_HEIGHT - MARGIN).append(" Td\n");
        for (Line line : lines) {
            stream.append(line.bold() ? "/F2 12 Tf\n" : "/F1 10 Tf\n");
            stream.append('(').append(escapePdf(line.text())).append(") Tj\nT*\n");
        }
        stream.append("ET\n");
        return stream.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    private static String escapePdf(String text) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' || c == '(' || c == ')') {
                out.append('\\').append(c);
            } else if (c >= 0x20 && c <= 0xFF) {
                out.append(c);
            } else {
                out.append('?'); // beta: Latin-1 only in PDF; HTML/DOCX carry full Unicode
            }
        }
        return out.toString();
    }

    private static byte[] obj(int id, String body) {
        return (id + " 0 obj\n" + body + "\nendobj\n").getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] streamObj(int id, byte[] stream) {
        byte[] head = (id + " 0 obj\n<< /Length " + stream.length + " >>\nstream\n")
                .getBytes(StandardCharsets.ISO_8859_1);
        byte[] tail = "endstream\nendobj\n".getBytes(StandardCharsets.ISO_8859_1);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(head);
        out.writeBytes(stream);
        out.writeBytes(tail);
        return out.toByteArray();
    }

    private static byte[] assemble(List<byte[]> objects) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] header = "%PDF-1.4\n".getBytes(StandardCharsets.ISO_8859_1);
        out.writeBytes(header);
        long[] offsets = new long[objects.size() + 1];
        long position = header.length;
        for (int i = 0; i < objects.size(); i++) {
            offsets[i + 1] = position;
            out.writeBytes(objects.get(i));
            position += objects.get(i).length;
        }
        long xrefStart = position;
        StringBuilder xref = new StringBuilder("xref\n0 ").append(objects.size() + 1).append('\n');
        xref.append("0000000000 65535 f \n");
        for (int i = 1; i <= objects.size(); i++) {
            xref.append(String.format("%010d 00000 n \n", offsets[i]));
        }
        xref.append("trailer\n<< /Size ").append(objects.size() + 1)
                .append(" /Root 1 0 R >>\nstartxref\n").append(xrefStart).append("\n%%EOF\n");
        out.writeBytes(xref.toString().getBytes(StandardCharsets.ISO_8859_1));
        return out.toByteArray();
    }
}
