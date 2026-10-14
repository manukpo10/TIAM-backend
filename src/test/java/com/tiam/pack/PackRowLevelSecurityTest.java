package com.tiam.pack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tiam.pack.domain.PackDownloadEvent;
import com.tiam.pack.domain.PackEdition;
import com.tiam.pack.domain.PackOrder;
import com.tiam.pack.domain.PackOrderStatus;
import com.tiam.pack.domain.PackTokens;
import com.tiam.pack.repository.PackDownloadEventRepository;
import com.tiam.pack.repository.PackOrderRepository;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The pack tables hold buyers' emails, names, phones and download tokens (a token is a credential that is worth
 * money). On Supabase every table in {@code public} is reachable through the Data API with the public "anon" key
 * unless row level security says otherwise, and the key is meant to be public. V14 turns RLS on, with NO policies,
 * for the three pack tables and takes their privileges away from anon / authenticated.
 *
 * <p>This is the proof that the backend is unaffected. It connects the way production does: as an ordinary login
 * role (not a superuser, no BYPASSRLS) that owns the tables because its own Flyway migrations created them. A table
 * owner is exempt from RLS unless the table is FORCEd, which V14 deliberately does not do. Everything the app does
 * (Hibernate schema validation, inserts, row locks, bulk updates, the FK from download events) runs here under that
 * role with RLS enabled. Skipped when Docker is not available.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
        "spring.datasource.username=tiam_backend",
        "spring.datasource.password=tiam_backend_test"
})
@Testcontainers(disabledWithoutDocker = true)
class PackRowLevelSecurityTest {

    static final List<String> PACK_TABLES = List.of("pack_orders", "pack_download_events", "pack_regret_requests");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withInitScript("rls/create-backend-role.sql");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    }

    @Autowired PackOrderRepository orders;
    @Autowired PackDownloadEventRepository downloads;
    @Autowired JdbcTemplate jdbc;

    // --- the setting that makes this a faithful reproduction ----------------------------------------------------

    @Test
    void theBackendRole_isAnOrdinaryRole_thatOwnsTheTables_andDoesNotBypassRls() {
        var role = jdbc.queryForMap("select current_user as name, r.rolsuper, r.rolbypassrls from pg_roles r where r.rolname = current_user");

        assertThat(role.get("name")).isEqualTo("tiam_backend");
        assertThat(role.get("rolsuper")).isEqualTo(false);
        assertThat(role.get("rolbypassrls")).isEqualTo(false);
        for (String table : PACK_TABLES) {
            assertThat(jdbc.queryForObject("select pg_get_userbyid(relowner) from pg_class where relname = ?", String.class, table))
                    .as("owner of %s", table).isEqualTo("tiam_backend");
        }
    }

    @Test
    void rowLevelSecurityIsOn_withoutPolicies_andIsNotForcedOnTheOwner() {
        for (String table : PACK_TABLES) {
            var flags = jdbc.queryForMap("select relrowsecurity, relforcerowsecurity from pg_class where relname = ?", table);

            assertThat(flags.get("relrowsecurity")).as("RLS on %s", table).isEqualTo(true);
            assertThat(flags.get("relforcerowsecurity")).as("FORCE on %s (would lock out the backend)", table).isEqualTo(false);
            assertThat(jdbc.queryForObject("select count(*) from pg_policies where tablename = ?", Integer.class, table))
                    .as("policies on %s", table).isZero();
        }
    }

    // --- the backend keeps working ---------------------------------------------------------------------------------

    @Test
    void theBackend_stillReadsWritesLocksAndUpdatesTheOrders() {
        PackOrder saved = orders.saveAndFlush(order("rls-backend@example.com"));

        assertThat(orders.findByAccessTokenAndActivoTrue(saved.getAccessToken())).isPresent();
        assertThat(orders.findWithLockById(saved.getId())).isPresent();                       // SELECT ... FOR UPDATE
        assertThat(orders.markEmailSent(saved.getId(), Instant.now())).isEqualTo(1);          // bulk UPDATE
        assertThat(orders.countByEmailAndStatusAndCreatedAtAfter("rls-backend@example.com",
                PackOrderStatus.PENDING, Instant.now().minusSeconds(60))).isEqualTo(1L);
        assertThat(orders.existsByEmailAndStatusInAndActivoTrue("rls-backend@example.com", List.of(PackOrderStatus.PENDING))).isTrue();
    }

    @Test
    void theBackend_stillWritesDownloadEventsAndRegretRequests() {
        PackOrder saved = orders.saveAndFlush(order("rls-events@example.com"));
        PackDownloadEvent event = new PackDownloadEvent();
        event.setOrderId(saved.getId());
        event.setFileKey("pack-completo-a4");

        downloads.saveAndFlush(event);
        jdbc.update("insert into pack_regret_requests(full_name, email) values ('Ana', 'rls-regret@example.com')");

        assertThat(downloads.countByOrderId(saved.getId())).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from pack_regret_requests where email = 'rls-regret@example.com'", Integer.class))
                .isEqualTo(1);
    }

    // --- and the public API is shut out ----------------------------------------------------------------------------------

    @Test
    void anon_andAuthenticated_cannotTouchAnyPackTable_orItsSequence() throws SQLException {
        orders.saveAndFlush(order("rls-anon@example.com"));

        try (Connection superuser = superuser()) {
            for (String role : List.of("anon", "authenticated")) {
                for (String table : PACK_TABLES) {
                    assertThat(hasPrivilege(superuser, role, table, "SELECT, INSERT, UPDATE, DELETE")).as("%s on %s", role, table).isFalse();
                    assertThat(queryAs(superuser, role, "select count(*) from " + table))
                            .as("%s reading %s", role, table).isEqualTo("42501"); // permission denied
                }
                assertThat(queryAs(superuser, role, "select nextval('pack_orders_id_seq')")).as("%s on the sequence", role).isEqualTo("42501");
            }
        }
    }

    @Test
    void evenIfAPrivilegeIsGrantedBackBySomeFutureMigration_rlsStillHidesEveryRow() throws SQLException {
        orders.saveAndFlush(order("rls-hidden@example.com"));

        try (Connection superuser = superuser(); Statement st = superuser.createStatement()) {
            st.execute("GRANT SELECT ON pack_orders TO anon");
            try {
                assertThat(countAs(superuser, "anon", "pack_orders")).as("rows visible to anon").isZero();
            } finally {
                st.execute("REVOKE SELECT ON pack_orders FROM anon");
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------------------------

    private static Connection superuser() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static boolean hasPrivilege(Connection c, String role, String table, String privileges) throws SQLException {
        try (var ps = c.prepareStatement("select has_table_privilege(?, ?, ?)")) {
            ps.setString(1, role);
            ps.setString(2, table);
            ps.setString(3, privileges);
            ResultSet rs = ps.executeQuery();
            rs.next();
            return rs.getBoolean(1);
        }
    }

    /** Runs a statement as the given role (the way the Data API would) and returns the SQLSTATE it failed with, or "ok". */
    private static String queryAs(Connection c, String role, String sql) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("SET ROLE " + role);
            try {
                st.execute(sql);
                return "ok";
            } catch (SQLException e) {
                return e.getSQLState();
            } finally {
                st.execute("RESET ROLE");
            }
        }
    }

    private static long countAs(Connection c, String role, String table) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("SET ROLE " + role);
            try (ResultSet rs = st.executeQuery("select count(*) from " + table)) {
                rs.next();
                return rs.getLong(1);
            } finally {
                st.execute("RESET ROLE");
            }
        }
    }

    private static PackOrder order(String email) {
        PackOrder order = new PackOrder();
        order.setAccessToken(PackTokens.newToken());
        order.setStatus(PackOrderStatus.PENDING);
        order.setEdition(PackEdition.FAMILIAS);
        order.setCurrency("ARS");
        order.setPackAmount(new BigDecimal("14990"));
        order.setTotalAmount(new BigDecimal("14990"));
        order.setBuyerName("Ana Gómez");
        order.setEmail(email);
        return order;
    }
}
