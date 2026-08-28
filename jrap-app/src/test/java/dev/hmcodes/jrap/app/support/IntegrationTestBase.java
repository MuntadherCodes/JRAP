package dev.hmcodes.jrap.app.support;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Boots the full application against an embedded PostgreSQL server.
 *
 * <p>The application datasource connects as the restricted {@code jrap_app} role created
 * by the V1 migration, so PostgreSQL row-level security is ACTIVE in every test —
 * exactly as in production. Flyway runs as the embedded superuser.</p>
 */
@SpringBootTest
public abstract class IntegrationTestBase {

    protected static final EmbeddedPostgres POSTGRES;

    static {
        try {
            POSTGRES = EmbeddedPostgres.start();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to start embedded PostgreSQL", e);
        }
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        String url = "jdbc:postgresql://localhost:" + POSTGRES.getPort() + "/postgres";
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> "jrap_app");
        registry.add("spring.datasource.password", () -> "jrap_app");
        registry.add("spring.flyway.url", () -> url);
        registry.add("spring.flyway.user", () -> "postgres");
        registry.add("spring.flyway.password", () -> "postgres");
    }
}
