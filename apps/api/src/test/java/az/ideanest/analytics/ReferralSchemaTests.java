package az.ideanest.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import az.ideanest.shared.Identifiers;
import az.ideanest.support.AbstractIntegrationTest;
import az.ideanest.support.Campaigns;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * What V24 refuses, and what it takes with a campaign.
 *
 * <p>The application refuses most of this too — {@code ReferralSource} will not build a
 * direct visit that names a place — and that is the point of asserting it here as well:
 * the value object is one writer, and a constraint is what makes the rule true of the
 * table rather than of the code path somebody remembered to go through. A fixture, a
 * support script, and a future module are all writers the value object never sees.
 *
 * <p>Deliberately not {@code @Transactional}: a statement that violates a constraint
 * aborts the surrounding transaction, so each of these needs its own — which a
 * {@link JdbcTemplate} against an auto-committing connection gives.
 */
class ReferralSchemaTests extends AbstractIntegrationTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    /** 32 bytes, which is what {@code referral_touches_visitor_hash_is_sha256} insists on. */
    private static final byte[] VISITOR = new byte[32];

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;

    private UUID projectId;

    @BeforeEach
    void campaign() {
        String handle = "referral-schema-" + SEQUENCE.incrementAndGet();
        projectId = Campaigns.seed(dataSource, Campaigns.creator(dataSource, handle), handle)
                .state("LIVE")
                .insert();
    }

    @AfterEach
    void clear() {
        jdbc().update("DELETE FROM projects WHERE id = ?", projectId);
    }

    // ------------------------------------------------------------------
    // What a visit may say
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a direct visit that also names a source is refused")
    void aDirectVisitThatNamesASourceIsRefused() {
        assertThatThrownBy(() -> insertTouch("DIRECT", "twitter", null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("a non-direct visit that names nothing is refused")
    void aNonDirectVisitThatNamesNothingIsRefused() {
        assertThatThrownBy(() -> insertTouch("SOCIAL", null, "launch-week", null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("a channel outside the vocabulary is refused")
    void anUnknownChannelIsRefused() {
        assertThatThrownBy(() -> insertTouch("CARRIER_PIGEON", "somewhere", null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("a window that closes before it opens is refused")
    void aWindowThatClosesBeforeItOpensIsRefused() {
        Instant now = Instant.now();
        assertThatThrownBy(() -> insertTouch(
                        Identifiers.newIdentifier(), "SOCIAL", "twitter", null, null, VISITOR, now, now))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("a visitor identifier that is not a SHA-256 is refused")
    void aVisitorIdentifierThatIsNotASha256IsRefused() {
        Instant now = Instant.now();
        assertThatThrownBy(() -> insertTouch(
                        Identifiers.newIdentifier(),
                        "SOCIAL",
                        "twitter",
                        null,
                        null,
                        new byte[16],
                        now,
                        now.plusSeconds(60)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("a label past sixty-four characters is refused")
    void anOverLongLabelIsRefused() {
        assertThatThrownBy(() -> insertTouch("SOCIAL", "s".repeat(65), null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ------------------------------------------------------------------
    // What an attribution may say
    // ------------------------------------------------------------------

    @Test
    @DisplayName("one pledge is attributed at most once")
    void onePledgeIsAttributedAtMostOnce() {
        UUID pledgeId = UUID.randomUUID();
        insertAttribution(pledgeId, "SOCIAL", "twitter", new BigDecimal("10.00"));

        // The backstop behind the listener's existence check, and the half that stays
        // true under two relays. OutboxMessage calls redelivery the contract.
        assertThatThrownBy(() -> insertAttribution(pledgeId, "EMAIL", "newsletter", new BigDecimal("10.00")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("an attributed pledge is worth something")
    void anAttributedPledgeIsWorthSomething() {
        assertThatThrownBy(
                        () -> insertAttribution(UUID.randomUUID(), "SOCIAL", "twitter", new BigDecimal("0.00")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("a currency that is not a code is refused")
    void aCurrencyThatIsNotACodeIsRefused() {
        assertThatThrownBy(() -> jdbc().update(
                        """
                        INSERT INTO referral_attributions (
                            id, pledge_id, project_id, channel, source, amount, currency,
                            pledged_at, attributed_at, event_id)
                        VALUES (?, ?, ?, 'SOCIAL', 'twitter', 10.00, 'manat', now(), now(), ?)
                        """,
                        Identifiers.newIdentifier(),
                        UUID.randomUUID(),
                        projectId,
                        UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ------------------------------------------------------------------
    // What goes with a campaign, and what survives a pruned visit
    // ------------------------------------------------------------------

    @Test
    @DisplayName("deleting a campaign takes its traffic and its attribution with it")
    void deletingACampaignTakesItsTrafficWithIt() {
        insertTouch("SOCIAL", "twitter", null, null);
        insertAttribution(UUID.randomUUID(), "SOCIAL", "twitter", new BigDecimal("10.00"));

        jdbc().update("DELETE FROM projects WHERE id = ?", projectId);

        assertThat(touchCount()).isZero();
        assertThat(attributionCount()).isZero();
    }

    @Test
    @DisplayName("an answer outlives the evidence it was derived from")
    void anAnswerOutlivesItsEvidence() {
        UUID touchId = insertTouch("SOCIAL", "twitter", null, null);
        UUID pledgeId = UUID.randomUUID();
        insertAttributionFrom(pledgeId, touchId);

        // What a retention sweep will do once one exists. The report must not move
        // when a visit past its window is removed, which is why the source is copied
        // onto the attribution rather than joined to.
        assertThatCode(() -> jdbc().update("DELETE FROM referral_touches WHERE id = ?", touchId))
                .doesNotThrowAnyException();

        assertThat(attributionCount()).isEqualTo(1);
        assertThat(jdbc().queryForObject(
                        "SELECT source FROM referral_attributions WHERE pledge_id = ?", String.class, pledgeId))
                .isEqualTo("twitter");
        assertThat(jdbc().queryForObject(
                        "SELECT touch_id FROM referral_attributions WHERE pledge_id = ?", UUID.class, pledgeId))
                .isNull();
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private UUID insertTouch(String channel, String source, String campaign, String referrerCode) {
        Instant now = Instant.now();
        return insertTouch(
                Identifiers.newIdentifier(),
                channel,
                source,
                campaign,
                referrerCode,
                VISITOR,
                now,
                now.plusSeconds(3600));
    }

    private UUID insertTouch(
            UUID id,
            String channel,
            String source,
            String campaign,
            String referrerCode,
            byte[] visitor,
            Instant occurredAt,
            Instant expiresAt) {

        jdbc().update(
                        """
                        INSERT INTO referral_touches (
                            id, project_id, visitor_hash, backer_id, channel, source, campaign,
                            referrer_code, occurred_at, expires_at)
                        VALUES (?, ?, ?, NULL, ?, ?, ?, ?, ?, ?)
                        """,
                        id,
                        projectId,
                        visitor,
                        channel,
                        source,
                        campaign,
                        referrerCode,
                        OffsetDateTime.ofInstant(occurredAt, ZoneOffset.UTC),
                        OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC));
        return id;
    }

    private void insertAttribution(UUID pledgeId, String channel, String source, BigDecimal amount) {
        jdbc().update(
                        """
                        INSERT INTO referral_attributions (
                            id, pledge_id, project_id, channel, source, amount, currency,
                            pledged_at, attributed_at, event_id)
                        VALUES (?, ?, ?, ?, ?, ?, 'AZN', now(), now(), ?)
                        """,
                        Identifiers.newIdentifier(),
                        pledgeId,
                        projectId,
                        channel,
                        source,
                        amount,
                        UUID.randomUUID());
    }

    private void insertAttributionFrom(UUID pledgeId, UUID touchId) {
        jdbc().update(
                        """
                        INSERT INTO referral_attributions (
                            id, pledge_id, project_id, touch_id, channel, source, amount, currency,
                            pledged_at, attributed_at, event_id)
                        VALUES (?, ?, ?, ?, 'SOCIAL', 'twitter', 10.00, 'AZN', now(), now(), ?)
                        """,
                        Identifiers.newIdentifier(),
                        pledgeId,
                        projectId,
                        touchId,
                        UUID.randomUUID());
    }

    private long touchCount() {
        Long count = jdbc().queryForObject(
                "SELECT count(*) FROM referral_touches WHERE project_id = ?", Long.class, projectId);
        return count == null ? 0 : count;
    }

    private long attributionCount() {
        Long count = jdbc().queryForObject(
                "SELECT count(*) FROM referral_attributions WHERE project_id = ?", Long.class, projectId);
        return count == null ? 0 : count;
    }

    private JdbcTemplate jdbc() {
        if (jdbc == null) {
            jdbc = new JdbcTemplate(dataSource);
        }
        return jdbc;
    }
}
