package dev.hmcodes.jrap.tenancy.tx;

import dev.hmcodes.jrap.common.tenant.TenantContext;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.Session;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Propagates the thread's tenant scope into every database transaction (FR-AUTH-3).
 *
 * <p>At transaction begin, the PostgreSQL settings {@code app.current_org} and
 * {@code app.system_access} are set transaction-locally ({@code set_config(..., true)}).
 * Row-level-security policies installed by the Flyway migration reference these settings,
 * so isolation is enforced by the database for every query on every tenant-scoped table —
 * the single mandatory filter the SRS requires. A transaction with no tenant and no
 * system access sees no tenant rows at all.</p>
 */
public class TenantAwareJpaTransactionManager extends JpaTransactionManager {

    public TenantAwareJpaTransactionManager(EntityManagerFactory emf) {
        super(emf);
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        super.doBegin(transaction, definition);
        EntityManagerHolder holder =
                (EntityManagerHolder) TransactionSynchronizationManager.getResource(getEntityManagerFactory());
        if (holder == null) {
            throw new IllegalStateException("No EntityManager bound after transaction begin");
        }
        String orgId = TenantContext.organisationId().map(Object::toString).orElse("");
        String systemAccess = TenantContext.hasSystemAccess() ? "on" : "off";
        Session session = holder.getEntityManager().unwrap(Session.class);
        session.doWork(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "select set_config('app.current_org', ?, true), set_config('app.system_access', ?, true)")) {
                statement.setString(1, orgId);
                statement.setString(2, systemAccess);
                statement.execute();
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to apply tenant scope to transaction", e);
            }
        });
    }
}
