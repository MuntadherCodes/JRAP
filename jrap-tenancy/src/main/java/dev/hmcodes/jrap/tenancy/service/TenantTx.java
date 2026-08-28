package dev.hmcodes.jrap.tenancy.service;

import dev.hmcodes.jrap.common.tenant.TenantContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

/**
 * Runs work in a NEW transaction under system-level tenant scope.
 *
 * <p>The tenant scope is written into the database as transaction-local settings at
 * transaction begin (see {@code TenantAwareJpaTransactionManager}), so scope changes made
 * inside an already-running transaction have no effect. Pre-authentication flows
 * (registration, login, token refresh, invitation acceptance) therefore must enter the
 * system scope BEFORE their transaction starts — which is exactly what this helper does.</p>
 */
@Component
public class TenantTx {

    private final TransactionTemplate template;

    public TenantTx(PlatformTransactionManager transactionManager) {
        this.template = new TransactionTemplate(transactionManager);
        this.template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public <T> T asSystem(Supplier<T> work) {
        return TenantContext.runAsSystem(() -> template.execute(status -> work.get()));
    }

    public void asSystem(Runnable work) {
        asSystem(() -> {
            work.run();
            return null;
        });
    }
}
