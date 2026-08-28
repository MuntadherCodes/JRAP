package dev.hmcodes.jrap.extract.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UtilsTest {

    @Test
    void scriptDetection() {
        assertThat(ScriptDetector.classify("Machine learning for diagnostics")).isEqualTo("ROMAN");
        assertThat(ScriptDetector.classify("التعلم الآلي في التشخيص الطبي")).isEqualTo("ARABIC");
        assertThat(ScriptDetector.romanShare("abc ابج")).isEqualTo(0.5);
        assertThat(ScriptDetector.classify("")).isEqualTo("UNKNOWN");
    }

    @Test
    void languageDetection() {
        assertThat(LanguageDetector.detect(
                "This study evaluates the performance of the proposed system and reports "
                        + "the results of the evaluation across multiple settings in detail."))
                .isEqualTo("en");
        assertThat(LanguageDetector.detect(
                "تقيّم هذه الدراسة أداء النظام المقترح وتعرض نتائج التقييم في بيئات متعددة بالتفصيل"))
                .isEqualTo("ar");
        assertThat(LanguageDetector.detect(null)).isEqualTo("unknown");
    }

    @Test
    void countryDetection() {
        assertThat(Countries.find("College of Medicine, University of Baghdad, Iraq")).contains("Iraq");
        assertThat(Countries.find("Dept. of CS, MIT, Cambridge, USA")).contains("United States");
        assertThat(Countries.find("King Saud University, KSA")).contains("Saudi Arabia");
        assertThat(Countries.find("Sorbonne Université, France")).contains("France");
        assertThat(Countries.find("Department of Mathematics")).isEmpty();
        // Word-boundary safety: 'Iraq' inside another word must not match.
        assertThat(Countries.find("Antiraqel Institute")).isEmpty();
    }

    @Test
    void nameNormalisation() {
        assertThat(NameNormalizer.normalize("Prof. Ali HASSAN")).isEqualTo("ali hassan");
        assertThat(NameNormalizer.normalize("Hassan, Ali")).isEqualTo("ali hassan");
        assertThat(NameNormalizer.normalize("Dr. Sára  Ahméd")).isEqualTo("sara ahmed");
        assertThat(NameNormalizer.normalize("A. B. Hassan")).isEqualTo("a b hassan");
        assertThat(NameNormalizer.normalize(null)).isEmpty();
    }

    @Test
    void textMatching() {
        assertThat(TextMatch.roughlyEqual("Machine Learning for Stub Diagnostics",
                "Machine learning for stub diagnostics.")).isTrue();
        assertThat(TextMatch.roughlyEqual("Machine learning for stub diagnostics",
                "Machine learning for stub diagnostics: a review")).isTrue();
        assertThat(TextMatch.roughlyEqual("Deep survey of stub networks",
                "Completely different archival title")).isFalse();
    }
}
