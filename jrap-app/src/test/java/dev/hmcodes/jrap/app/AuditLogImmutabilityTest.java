package dev.hmcodes.jrap.app;

import dev.hmcodes.jrap.app.support.IntegrationTestBase;
import dev.hmcodes.jrap.common.tenant.TenantContext;
import dev.hmcodes.jrap.tenancy.service.SecurityAuditService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FR-AUTH-5: the security audit log is write-once. The database trigger rejects
 * UPDATE and DELETE even for the schema owner/superuser, so no application bug or
 * privileged connection can rewrite history.
 */
class AuditLogImmutabilityTest extends IntegrationTestBase {

    @Autowired SecurityAuditService audit;

    @Test
    void auditEntriesCannotBeAlteredOrRemoved() throws SQLException {
        TenantContext.runAsSystem(() ->
                audit.record("TEST_EVENT", null, null, "immutability@test.example", Map.of("k", "v"), null));

        // Inspect and attack as the SUPERUSER — RLS does not apply, but the trigger must.
        try (Connection superuser = POSTGRES.getPostgresDatabase().getConnection()) {
            try (PreparedStatement count = superuser.prepareStatement(
                    "select count(*) from security_audit_log where actor_email = ?")) {
                count.setString(1, "immutability@test.example");
                try (ResultSet rs = count.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getLong(1)).isPositive();
                }
            }

            assertThatThrownBy(() -> {
                try (PreparedStatement update = superuser.prepareStatement(
                        "update security_audit_log set event_type = 'TAMPERED' where actor_email = ?")) {
                    update.setString(1, "immutability@test.example");
                    update.executeUpdate();
                }
            }).isInstanceOf(SQLException.class).hasMessageContaining("immutable");

            assertThatThrownBy(() -> {
                try (PreparedStatement delete = superuser.prepareStatement(
                        "delete from security_audit_log where actor_email = ?")) {
                    delete.setString(1, "immutability@test.example");
                    delete.executeUpdate();
                }
            }).isInstanceOf(SQLException.class).hasMessageContaining("immutable");
        }
    }
}
