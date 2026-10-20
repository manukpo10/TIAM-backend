package com.tiam.pack;

import static org.assertj.core.api.Assertions.assertThat;

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
 * Selling the Cuadernos needs NO schema change: {@code pack_orders.edition} is a free VARCHAR(20) (no CHECK) and the
 * enum is stored by name. This pins that down against the real migrations, so a future constraint on the column cannot
 * silently break the product, and checks that the tables the product uses keep their row level security. Skipped when
 * Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
class CuadernosMigrationTest {

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
    void anOrderForTheCuadernosEdition_canBeStored_withTheSameDefaultsAsAnyPackOrder() throws SQLException {
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(
                "insert into pack_orders(access_token, edition, pack_amount, games_amount, total_amount, buyer_name, email, with_games) "
                        + "values ('tok-cuadernos-1', 'CUADERNOS', 20000, 15000, 35000, 'Ana', 'ana@example.com', true) "
                        + "returning status, currency, catalog_version, activo, with_extra, extra_amount")) {
            ResultSet rs = ps.executeQuery();
            rs.next();
            assertThat(rs.getString("status")).isEqualTo("PENDING");
            assertThat(rs.getString("currency")).isEqualTo("ARS");
            assertThat(rs.getString("catalog_version")).isEqualTo("v1");
            assertThat(rs.getBoolean("activo")).isTrue();
            assertThat(rs.getBoolean("with_extra")).isFalse();
            assertThat(rs.getBigDecimal("extra_amount")).isEqualByComparingTo("0");
        }
    }

    @Test
    void theEditionColumn_hasNoCheckConstraint_andFitsTheNewName() throws SQLException {
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(
                "select count(*) from pg_constraint where conrelid = 'pack_orders'::regclass and contype = 'c'")) {
            ResultSet rs = ps.executeQuery();
            rs.next();
            assertThat(rs.getInt(1)).isZero();
        }
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(
                "select character_maximum_length from information_schema.columns "
                        + "where table_name = 'pack_orders' and column_name = 'edition'")) {
            ResultSet rs = ps.executeQuery();
            rs.next();
            assertThat(rs.getInt(1)).isGreaterThanOrEqualTo("CUADERNOS".length());
        }
    }

    @Test
    void packTablesKeepTheirRowLevelSecurity_andNoNewTableWasAdded() throws SQLException {
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(
                "select relname from pg_class where relkind = 'r' and relnamespace = 'public'::regnamespace "
                        + "and relname like 'pack\\_%' and not relrowsecurity")) {
            ResultSet rs = ps.executeQuery();
            assertThat(rs.next()).as("a pack_* table without row level security").isFalse();
        }
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(
                "select count(*) from pg_class where relkind = 'r' and relnamespace = 'public'::regnamespace and relname like 'pack\\_%'")) {
            ResultSet rs = ps.executeQuery();
            rs.next();
            assertThat(rs.getInt(1)).as("pack_orders, pack_download_events, pack_regret_requests").isEqualTo(3);
        }
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
