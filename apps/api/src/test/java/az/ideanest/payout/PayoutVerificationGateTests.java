package az.ideanest.payout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import az.ideanest.compliance.application.ComplianceOverrides;
import az.ideanest.payout.application.CreatorNotVerifiedException;
import az.ideanest.payout.application.PayoutService;
import az.ideanest.payout.domain.Payout;
import az.ideanest.payout.domain.PayoutState;
import az.ideanest.payout.infrastructure.PayoutRepository;
import az.ideanest.shared.compliance.ComplianceRequirement;
import az.ideanest.shared.compliance.CreatorStandings;
import az.ideanest.shared.compliance.VerificationStanding;
import az.ideanest.shared.money.Money;
import az.ideanest.shared.EmailAddress;
import az.ideanest.support.AbstractIntegrationTest;
import az.ideanest.support.Campaigns;
import az.ideanest.support.Verifications;
import az.ideanest.verification.domain.VerificationState;
import az.ideanest.user.infrastructure.UserRepository;
import az.ideanest.verification.infrastructure.IdentityVerificationRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Money does not leave to somebody the platform has not identified — issue #431.
 *
 * <p>The gate is on the payout and not on submission, and #431 gives two reasons. A campaign
 * that never reaches its goal collects nothing, so gating submission would send every creator
 * through document review to find out whether their idea funds. And §6.3 already holds a payout
 * for fourteen days, so a verification in progress fits inside a window that is already there.
 *
 * <p><strong>Every case below turns on the standing rather than on the row's state.</strong>
 * That is the design {@code IdentityStandings} argues for: an approval that has aged out reads
 * as expired whether or not any sweep has moved it, because a gate that waits for a job is open
 * for as long as the job is broken.
 */
@DisplayName("The payout's identity gate")
class PayoutVerificationGateTests extends AbstractIntegrationTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final Money HUNDRED = Money.of(new BigDecimal("100.00"), "AZN");
    private static final String ADMINISTRATOR_EMAIL = "moderator@ideanest.test";

    @Autowired
    private PayoutService payouts;

    @Autowired
    private PayoutRepository payoutRows;

    @Autowired
    private CreatorStandings standings;

    @Autowired
    private ComplianceOverrides overrides;

    @Autowired
    private IdentityVerificationRepository verifications;

    @Autowired
    private UserRepository users;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private DataSource dataSource;

    @AfterEach
    void clear() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("DELETE FROM payout_approvals");
        jdbc.update("DELETE FROM payouts");
        jdbc.update("DELETE FROM compliance_overrides");
        jdbc.update("DELETE FROM identity_verifications");
        Campaigns.clear(dataSource);
    }

    // ------------------------------------------------------------------
    // What releases, and what holds
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a verified creator's payout is released from the hold and can be approved")
    void verifiedReleases() {
        Held held = heldPayout();
        Verifications.approve(dataSource, held.creatorId(), administrator());

        assertThat(standings.of(held.creatorId())).isEqualTo(VerificationStanding.VERIFIED);

        payouts.queue(administrator(), 0);
        assertThat(state(held.payoutId())).isEqualTo(PayoutState.PENDING_APPROVAL);

        payouts.approve(administrator(), held.payoutId(), "checked");
        assertThat(state(held.payoutId())).isEqualTo(PayoutState.APPROVED);
    }

    @Test
    @DisplayName("an unverified creator's payout stays in the hold rather than failing")
    void unverifiedHolds() {
        Held held = heldPayout();

        assertThat(standings.of(held.creatorId())).isEqualTo(VerificationStanding.NEVER_REQUESTED);

        payouts.queue(administrator(), 0);

        // CALCULATED and not FAILED or CANCELLED. The money is owed; what is missing is a
        // document, and a payout that failed would have to be recalculated to pay somebody
        // the platform always intended to pay.
        assertThat(state(held.payoutId())).isEqualTo(PayoutState.CALCULATED);
    }

    @Test
    @DisplayName("listing the queue asks the creator for documents, so the hold is not silent")
    void theHoldAsksTheCreatorForSomething() {
        Held held = heldPayout();

        payouts.queue(administrator(), 0);

        // V55's argument, which #431 quotes: a screen that simply shows nothing "makes the same
        // campaign look as though nothing is owed". The creator's half of that is being asked.
        assertThat(verifications.findByUserId(held.creatorId()))
                .isPresent()
                .get()
                .satisfies(row -> assertThat(row.getState()).isEqualTo(VerificationState.REQUESTED));
    }

    @Test
    @DisplayName("approving an unverified creator's payout is refused, and the refusal says which standing")
    void approvingIsRefused() {
        Held held = heldPayout();

        assertThatThrownBy(() -> payouts.approve(administrator(), held.payoutId(), "let it go"))
                .isInstanceOf(CreatorNotVerifiedException.class)
                .satisfies(thrown -> assertThat(((CreatorNotVerifiedException) thrown).standing())
                        .isEqualTo(VerificationStanding.NEVER_REQUESTED));

        // The gate is in `approve` as well as in the sweep, because an operator may reach a
        // payout the sweep has not, and a gate in one place depends on which got there first.
        assertThat(state(held.payoutId())).isEqualTo(PayoutState.CALCULATED);
    }

    // ------------------------------------------------------------------
    // Expiry is not failure
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an approval that has aged out holds the payout rather than failing it")
    void expiredHolds() {
        Held held = heldPayout();
        Verifications.expired(dataSource, held.creatorId(), administrator());

        // Read from the comparison, not from the row's state: the sweep has not run and the
        // row still says APPROVED.
        assertThat(verifications.findByUserId(held.creatorId()).orElseThrow().getState())
                .isEqualTo(VerificationState.APPROVED);
        assertThat(standings.of(held.creatorId())).isEqualTo(VerificationStanding.EXPIRED);

        payouts.queue(administrator(), 0);

        // #431: "Failing it permanently would strand money that is owed, over a document that
        // was fine last year."
        assertThat(state(held.payoutId())).isEqualTo(PayoutState.CALCULATED);
        assertThatThrownBy(() -> payouts.approve(administrator(), held.payoutId(), "aged out"))
                .isInstanceOf(CreatorNotVerifiedException.class);
    }

    @Test
    @DisplayName("documents in the review queue hold the payout, and the creator is not asked again")
    void underReviewHolds() {
        Held held = heldPayout();
        Verifications.underReview(dataSource, held.creatorId());

        assertThat(standings.of(held.creatorId())).isEqualTo(VerificationStanding.UNDER_REVIEW);

        payouts.queue(administrator(), 0);

        assertThat(state(held.payoutId())).isEqualTo(PayoutState.CALCULATED);
        // Asking somebody to send documents they have already sent is how a queue gets four
        // copies of the same passport.
        assertThat(verifications.findByUserId(held.creatorId()).orElseThrow().getState())
                .isEqualTo(VerificationState.SUBMITTED);
    }

    // ------------------------------------------------------------------
    // The override
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a live override releases the payout, and is not drawn as though somebody checked a document")
    void aWaiverReleases() {
        Held held = heldPayout();

        overrides.grant(
                administrator(),
                held.creatorId(),
                ComplianceRequirement.IDENTITY_VERIFICATION,
                az.ideanest.compliance.domain.OverrideReason.DOCUMENT_UNAVAILABLE_ABROAD,
                "Passport is with the embassy in Ankara until October.",
                Instant.now().plus(30, ChronoUnit.DAYS));

        // WAIVED and not VERIFIED. The two are answers a regulator would read very
        // differently, and the console must not draw a waiver as a document review.
        assertThat(standings.of(held.creatorId())).isEqualTo(VerificationStanding.WAIVED);

        payouts.queue(administrator(), 0);
        assertThat(state(held.payoutId())).isEqualTo(PayoutState.PENDING_APPROVAL);
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private PayoutState state(UUID payoutId) {
        return payoutRows.findById(payoutId).orElseThrow().state();
    }

    /** A payout past its hold, which nothing but the gate now stands between and approval. */
    private Held heldPayout() {
        String unique = "payout-gate" + SEQUENCE.incrementAndGet();
        UUID creatorId = Campaigns.creator(dataSource, unique);
        UUID projectId = Campaigns.seed(dataSource, creatorId, unique)
                .state("SUCCESSFUL")
                .goal("100.00")
                .insert();

        Payout payout = Payout.calculated(
                projectId,
                creatorId,
                HUNDRED,
                Money.zero("AZN"),
                Money.zero("AZN"),
                Money.zero("AZN"),
                HUNDRED,
                null,
                Instant.parse("2026-01-01T00:00:00Z"),
                (short) 1,
                "payout-key-" + UUID.randomUUID());

        return new Held(payoutRows.save(payout).id(), creatorId);
    }

    /**
     * The bootstrap staff account, registered once and reused.
     *
     * <p>A dozen suites share this address. It is registered rather than signed in for, because
     * sign-ins per email are limited to five and a suite that signed in would fail whenever it
     * happened to run late in the pass.
     */
    private UUID administrator() {
        EmailAddress email = EmailAddress.of(ADMINISTRATOR_EMAIL);
        if (users.findByEmailAndDeletedAtIsNull(email).isEmpty()) {
            rest.postForEntity(
                    "/v1/auth/register",
                    Map.of(
                            "email", email.value(),
                            "password", "a-long-enough-password",
                            "name", "Test Administrator"),
                    String.class);
        }
        return users.findByEmailAndDeletedAtIsNull(email).orElseThrow().getId();
    }

    private record Held(UUID payoutId, UUID creatorId) {
    }
}
