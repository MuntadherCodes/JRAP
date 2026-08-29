package dev.hmcodes.jrap.registry.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Typed access to global platform settings (FR-ADM-1). app_setting has no tenant
 * scope — reads happen on hot paths (crawl, analysis), writes only through the
 * platform-admin API. Values are cached briefly to keep the crawl loop cheap.
 */
@Service
public class SettingsService {

    public static final String CRAWL_BLOCKLIST = "crawl.blocklist";
    public static final String RUBRIC_VERSION = "analysis.rubric-version";
    public static final String FEATURE_FLAGS = "feature.flags";

    private static final long CACHE_MILLIS = 5000;

    private final AppSettingRepository settings;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    private volatile long cachedAt;
    private volatile Map<String, String> cache = Map.of();

    public SettingsService(AppSettingRepository settings, ObjectMapper objectMapper, Clock clock) {
        this.settings = settings;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Optional<String> raw(String key) {
        long now = clock.millis();
        if (now - cachedAt > CACHE_MILLIS) {
            Map<String, String> fresh = new LinkedHashMap<>();
            settings.findAll().forEach(s -> fresh.put(s.getKey(), s.getValue()));
            cache = fresh;
            cachedAt = now;
        }
        return Optional.ofNullable(cache.get(key));
    }

    @Transactional
    public void put(String key, String jsonValue, UUID updatedBy) {
        try {
            objectMapper.readTree(jsonValue); // must be valid JSON
        } catch (Exception e) {
            throw new IllegalArgumentException("Setting value must be valid JSON");
        }
        settings.findById(key).ifPresentOrElse(
                existing -> existing.update(jsonValue, updatedBy, clock.instant()),
                () -> settings.save(new AppSetting(key, jsonValue, updatedBy, clock.instant())));
        cachedAt = 0; // bust cache
    }

    @Transactional(readOnly = true)
    public Map<String, String> all() {
        Map<String, String> out = new LinkedHashMap<>();
        settings.findAll().forEach(s -> out.put(s.getKey(), s.getValue()));
        return out;
    }

    /** Hosts the crawler must not fetch from (FR-ADM-1 crawl blocklist). */
    public List<String> crawlBlocklist() {
        return raw(CRAWL_BLOCKLIST).map(json -> {
            List<String> hosts = new ArrayList<>();
            try {
                JsonNode array = objectMapper.readTree(json);
                for (JsonNode node : array) {
                    hosts.add(node.asText().toLowerCase(Locale.ROOT));
                }
            } catch (Exception ignored) {
                // malformed setting: fail open (no blocklist) rather than block all crawling
            }
            return hosts;
        }).orElse(List.of());
    }

    /** Admin-rolled rubric version override (FR-ADM-1: "admin can roll a rubric version"). */
    public Optional<String> rubricVersionOverride() {
        return raw(RUBRIC_VERSION).map(json -> {
            try {
                return objectMapper.readTree(json).asText();
            } catch (Exception e) {
                return null;
            }
        }).filter(v -> v != null && !v.isBlank());
    }
}
