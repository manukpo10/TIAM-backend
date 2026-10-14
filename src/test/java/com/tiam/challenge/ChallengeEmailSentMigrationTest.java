package com.tiam.challenge;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * V11 adds {@code email_sent_at}. Buyers who paid BEFORE the migration must be marked as "already handled":
 * the retry job (ChallengeAccessEmailRetryService) mails every PAID purchase of the last 72 hours whose
 * {@code email_sent_at} is NULL, so without a backfill the day the email provider is configured it would send
 * "tu Desafío está listo" to customers who already activated by WhatsApp.
 *
 * <p>Skipped (not failed) when Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
class ChallengeEmailSentMigrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @BeforeEach
    void startFromAnEmptyDatabase() {
        flyway("11").clean();
    }

    @Test
    void v11_marksEveryAlreadyPaidPurchaseAsHandled_andLeavesTheRestAlone() throws SQLException {
        flyway("10").migrate();
        long paid = insertPurchase("PAID", "paid@example.com");
        long paidWithoutEmail = insertPurchase("PAID", null);
        long pending = insertPurchase("PENDING", "pending@example.com");
        long failed = insertPurchase("FAILED", "failed@example.com");

        flyway("11").migrate();

        assertThat(emailSentAtIsSet(paid)).as("PAID with email").isTrue();
        assertThat(emailSentAtIsSet(paidWithoutEmail)).as("PAID without email").isTrue();
        assertThat(emailSentAtIsSet(pending)).as("PENDING must stay NULL: it may still be paid later").isFalse();
        assertThat(emailSentAtIsSet(failed)).as("FAILED stays NULL").isFalse();
    }

    @Test
    void v11_doesNotTouchPurchasesPaidAfterTheMigration() throws SQLException {
        flyway("11").migrate();
        long paidLater = insertPurchase("PAID", "later@example.com");

        assertThat(emailSentAtIsSet(paidLater))
                .as("a purchase paid after V11 must still get its email").isFalse();
    }

    private static Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .target(target)
                .cleanDisabled(false)
                .load();
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static long insertPurchase(String status, String email) throws SQLException {
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(
                "insert into challenge_purchases(buyer_name, phone, email, status, access_token, purchase_date) "
                        + "values ('Ana', '5492211111111', ?, ?, ?, now()) returning id")) {
            ps.setString(1, email);
            ps.setString(2, status);
            ps.setString(3, "tok-" + System.nanoTime());
            ResultSet rs = ps.executeQuery();
            rs.next();
            return rs.getLong(1);
        }
    }

    private static boolean emailSentAtIsSet(long id) throws SQLException {
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(
                "select email_sent_at is not null from challenge_purchases where id = ?")) {
            ps.setLong(1, id);
            ResultSet rs = ps.executeQuery();
            rs.next();
            return rs.getBoolean(1);
        }
    }
}
