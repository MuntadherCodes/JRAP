package dev.hmcodes.jrap.extract.parse;

import java.util.List;

/** Parser output before persistence (FR-EXT-1). */
public record ParsedMember(String name, String role, String institution, String country,
                           List<String> profileLinks, double confidence, String excerpt) {
}
