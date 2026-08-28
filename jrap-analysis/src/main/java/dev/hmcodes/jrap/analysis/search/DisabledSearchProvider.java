package dev.hmcodes.jrap.analysis.search;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/** Default: no web-search provider configured (jrap.search.provider=disabled). */
@Component
@ConditionalOnProperty(name = "jrap.search.provider", havingValue = "disabled", matchIfMissing = true)
public class DisabledSearchProvider implements SearchProvider {

    @Override
    public List<SearchHit> search(String query, int limit) {
        return List.of();
    }

    @Override
    public boolean isEnabled() {
        return false;
    }
}
