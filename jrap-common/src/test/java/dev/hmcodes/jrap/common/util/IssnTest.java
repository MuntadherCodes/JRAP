package dev.hmcodes.jrap.common.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IssnTest {

    @Test
    void validIssnsNormalise() {
        assertThat(Issn.normalise("2708-9134")).isEqualTo("2708-9134");
        assertThat(Issn.normalise("27089134")).isEqualTo("2708-9134");
        assertThat(Issn.normalise(" 2708-9126 ")).isEqualTo("2708-9126");
        assertThat(Issn.normalise("0378-5955")).isEqualTo("0378-5955"); // ISSN standard's own example
        assertThat(Issn.normalise("2434-561x")).isEqualTo("2434-561X"); // X check digit, case-folded
    }

    @Test
    void invalidIssnsAreRejected() {
        assertThat(Issn.normalise("1234-5678")).isNull();  // bad checksum
        assertThat(Issn.normalise("2024-2025")).isNull();  // year range
        assertThat(Issn.normalise("abcd-efgh")).isNull();
        assertThat(Issn.normalise(null)).isNull();
        assertThat(Issn.normalise("")).isNull();
    }
}
