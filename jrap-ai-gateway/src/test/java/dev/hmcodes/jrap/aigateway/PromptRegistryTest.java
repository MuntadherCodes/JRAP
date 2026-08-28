package dev.hmcodes.jrap.aigateway;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PromptRegistryTest {

    @Test
    void rendersActivePromptWithVariables() {
        PromptRegistry registry = new PromptRegistry("");
        String rendered = registry.render("board-extraction", Map.of("page_text", "SOME PAGE TEXT"));
        assertThat(rendered).contains("SOME PAGE TEXT");
        assertThat(rendered).doesNotContain("{{page_text}}");
        assertThat(registry.activeVersion("board-extraction")).isEqualTo("v1");
    }

    @Test
    void versionPinningIsParsedFromConfig() {
        PromptRegistry registry = new PromptRegistry("board-extraction=v9,article-extraction=v1");
        assertThat(registry.activeVersion("board-extraction")).isEqualTo("v9");
        assertThat(registry.activeVersion("article-extraction")).isEqualTo("v1");
        assertThat(registry.activeVersion("unpinned")).isEqualTo("v1");
        assertThatThrownBy(() -> registry.render("board-extraction", Map.of()))
                .hasMessageContaining("v9"); // pinned version has no template on the classpath
    }
}
