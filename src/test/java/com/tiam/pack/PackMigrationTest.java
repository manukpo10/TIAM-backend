package com.tiam.pack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Applies every Flyway migration to a real Postgres and checks what V12 promises. Skipped (not failed)
 * when Docker is not available, so the rest of the suite stays runnable on a machine without it.
 */
@Testcontainers(disabledWithoutDocker = true)
class PackMigrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @Test
    void v12_isTheLatestAppliedMigration() throws SQLException {
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(
                "select max(version::int) from flyway_schema_history where success")) {
            ResultSet rs = ps.executeQuery();
            rs.next();
            assertThat(rs.getInt(1)).isGreaterThanOrEqualTo(12);
        }
    }

    @Test
    void newOrder_getsSafeDefaults() throws SQLException {
        try (Connection c = connect()) {
            long id = insertOrder(c, "tok-defaults", null);

            try (PreparedStatement ps = c.prepareStatement(
                    "select status, currency, catalog_version, activo, with_games, with_extra, games_amount "
                            + "from pack_orders where id = ?")) {
                ps.setLong(1, id);
                ResultSet rs = ps.executeQuery();
                rs.next();
                assertThat(rs.getString("status")).isEqualTo("PENDING");
                assertThat(rs.getString("currency")).isEqualTo("ARS");
                assertThat(rs.getString("catalog_version")).isEqualTo("v1");
                assertThat(rs.getBoolean("activo")).isTrue();
                assertThat(rs.getBoolean("with_games")).isFalse();
                assertThat(rs.getBoolean("with_extra")).isFalse();
                assertThat(rs.getBigDecimal("games_amount")).isEqualByComparingTo("0");
            }
        }
    }

    @Test
    void mpPaymentId_isUniqueWhenPresent_soOnePaymentCannotCreditTwoOrders() throws SQLException {
        try (Connection c = connect()) {
            insertOrder(c, "tok-pay-1", "PAY-123");

            assertThatThrownBy(() -> insertOrder(c, "tok-pay-2", "PAY-123"))
                    .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("23505"));
        }
    }

    @Test
    void mpPaymentId_canBeNullOnManyOrders_becauseTheIndexIsPartial() throws SQLException {
        try (Connection c = connect()) {
            insertOrder(c, "tok-null-1", null);
            insertOrder(c, "tok-null-2", null);
            insertOrder(c, "tok-null-3", null);
        }
    }

    @Test
    void accessToken_isUnique() throws SQLException {
        try (Connection c = connect()) {
            insertOrder(c, "tok-dup", null);

            assertThatThrownBy(() -> insertOrder(c, "tok-dup", null))
                    .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("23505"));
        }
    }

    @Test
    void nextAccessToken_isUniqueWhenPresent_butManyOrdersMayHaveNone() throws SQLException {
        try (Connection c = connect()) {
            long a = insertOrder(c, "tok-next-a", null);
            long b = insertOrder(c, "tok-next-b", null);
            setNextToken(c, a, "replacement-1");

            assertThatThrownBy(() -> setNextToken(c, b, "replacement-1"))
                    .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("23505"));
            insertOrder(c, "tok-next-c", null); // a NULL next token is the normal case and never collides
            insertOrder(c, "tok-next-d", null);
        }
    }

    private static void setNextToken(Connection c, long orderId, String token) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "update pack_orders set next_access_token = ?, next_access_token_issued_at = now() where id = ?")) {
            ps.setString(1, token);
            ps.setLong(2, orderId);
            ps.executeUpdate();
        }
    }

    @Test
    void downloadEvent_requiresAnExistingOrder() throws SQLException {
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(
                "insert into pack_download_events(order_id, file_key) values (?, ?)")) {
            ps.setLong(1, 999_999L);
            ps.setString(2, "pack-completo-a4");

            assertThatThrownBy(ps::executeUpdate)
                    .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("23503"));
        }
    }

    @Test
    void regretRequest_canBeStoredWithoutAnOrder() throws SQLException {
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(
                "insert into pack_regret_requests(full_name, email) values ('Ana', 'ana@example.com') returning status")) {
            ResultSet rs = ps.executeQuery();
            rs.next();
            assertThat(rs.getString(1)).isEqualTo("RECEIVED");
        }
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static long insertOrder(Connection c, String token, String mpPaymentId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "insert into pack_orders(access_token, edition, pack_amount, total_amount, buyer_name, email, mp_payment_id) "
                        + "values (?, 'FAMILIAS', 14990, 14990, 'Ana', 'ana@example.com', ?) returning id")) {
            ps.setString(1, token);
            ps.setString(2, mpPaymentId);
            ResultSet rs = ps.executeQuery();
            rs.next();
            return rs.getLong(1);
        }
    }
}
