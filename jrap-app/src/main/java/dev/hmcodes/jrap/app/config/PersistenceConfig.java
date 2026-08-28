package dev.hmcodes.jrap.app.config;

import dev.hmcodes.jrap.tenancy.tx.TenantAwareJpaTransactionManager;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Installs the tenant-aware transaction manager so every transaction carries the
 * caller's tenant scope into PostgreSQL row-level security (FR-AUTH-3).
 */
@Configuration
public class PersistenceConfig {

    @Bean
    public PlatformTransactionManager transactionManager(EntityManagerFactory entityManagerFactory) {
        return new TenantAwareJpaTransactionManager(entityManagerFactory);
    }
}
