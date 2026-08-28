package dev.hmcodes.jrap.analysis.rubric;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RubricLoaderTest {

    @Test
    void loadsActiveRubricWithThresholdsAndDeltas() {
        Rubric rubric = new RubricLoader(new ObjectMapper(), "1.0").active();
        assertThat(rubric.version()).isEqualTo("1.0");
        assertThat(rubric.threshold("singleAuthorShareMax")).isEqualTo(0.40);
        assertThat(rubric.delta("regularity.gapYear")).isEqualTo(2);
        assertThatThrownBy(() -> rubric.threshold("nonexistent"))
                .hasMessageContaining("nonexistent");
    }

    @Test
    void unknownVersionFailsLoudly() {
        assertThatThrownBy(() -> new RubricLoader(new ObjectMapper(), "9.9").active())
                .hasMessageContaining("v9.9");
    }
}
