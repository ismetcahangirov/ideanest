package az.ideanest.pledge;

import static org.assertj.core.api.Assertions.assertThat;

import az.ideanest.shared.idempotency.IdempotencyKeySweeper;
import az.ideanest.shared.idempotency.IdempotencyProperties;
import az.ideanest.support.AbstractIntegrationTest;
import az.ideanest.support.AdjustableClock;
import az.ideanest.support.Campaigns;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The checkout over HTTP: drafting a pledge, reading it, and confirming it.
 *
 * <p><strong>Three of these carry the issue.</strong>
 *
 * <ul>
 *   <li>{@link #aReplayedKeyReturnsTheOriginalResponse()} is what #52 is for. It
 *       asserts the two bodies are <em>equal as bytes</em> rather than equivalent as
 *       objects, because "the same response" is the promise and a re-serialisation
 *       that happens to agree today is not it.
 *   <li>{@link #twoIdenticalRequestsAtOnceProduceOnePledge()} is the property no
 *       amount of careful code can give without the database. Two real threads, one
 *       key, one PostgreSQL, and exactly one pledge — the assertion that fails under
 *       every implementation that reads before it writes.
 *   <li>{@link #confirmingMovesTheHeldPlaceToAClaimedOne()} checks both stock columns
 *       either side of one statement, which is the invariant
 *       {@code RewardTierRepository#commitOnePlace} exists to hold.
 * </ul>
 *
 * <p>Money is asserted as the strings the API answers with, never parsed into a
 * double. §10.3 makes the amount a string precisely so that a client cannot do
 * otherwise, and a test that parsed it would be checking a value the contract does
 * not promise.
 *
 * <p>Time is the injected {@link AdjustableClock}. A test that waits five minutes for
 * a reservation, or a day for §17.2's retention, is a test nobody runs.
 */
class PledgeApiTests extends AbstractIntegrationTest {

    /** Distinguishes the accounts these tests create; see {@code PledgeSchemaTests} for why it is a counter. */
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private static final String PASSWORD = "a-long-enough-password";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private AdjustableClock clock;

    @Autowired
    private PledgeProperties properties;

    @Autowired
    private IdempotencyKeySweeper sweeper;

    @Autowired
    private IdempotencyProperties idempotency;

    private JdbcTemplate jdbc;

    private JdbcTemplate jdbc() {
        if (jdbc == null) {
            jdbc = new JdbcTemplate(dataSource);
        }
        return jdbc;
    }

    @AfterEach
    void clearCheckouts() {
        // In dependency order rather than by cascade, because that is the cleanup and
        // not the assertion -- PledgeSchemaTests is where the cascades are checked.
        jdbc().update("DELETE FROM pledge_addons");
        jdbc().update("DELETE FROM pledges");
        jdbc().update("DELETE FROM idempotency_keys");
        jdbc().update("DELETE FROM shipping_rules");
        jdbc().update("DELETE FROM reward_tiers");
        jdbc().update("DELETE FROM project_state_transitions");
        jdbc().update("DELETE FROM projects");
        // The clock is a bean the whole suite shares. A test that froze it and did not
        // let it go would stop every session, token, and reservation after it.
        clock.reset();
    }

    // -----------------------------------------------------------------------
    // The money
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("a draft answers with all five amounts and the total the database generated")
    void aDraftCarriesTheWholeQuote() {
        Account creator = account("creator");
        UUID projectId = project(creator);
        UUID rewardId = reward(creator, projectId, "A boxed set", "45.00", tier -> {
            tier.put("shippingType", "DOMESTIC");
            tier.put("limitQuantity", 10);
        });
        shipTo(creator, rewardId, "AZ", "5.00", "2.00");
        UUID addonId = reward(creator, projectId, "An enamel mug", "15.00", tier -> {
            tier.put("shippingType", "DOMESTIC");
            tier.put("isAddon", true);
        });
        shipTo(creator, addonId, "AZ", "3.00", "1.00");
        Campaigns.launch(dataSource, projectId);

        Account backer = account("backer");
        Map<String, Object> body = draftBody(projectId, rewardId, "50.00");
        body.put("shippingCountry", "AZ");
        body.put("addons", List.of(Map.of("rewardTierId", addonId.toString(), "quantity", 2)));

        ResponseEntity<String> created = post("/v1/pledges/draft", backer, newKey(), body);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        Map<String, Object> pledge = parse(created);
        assertThat(pledge.get("state")).isEqualTo("DRAFT");
        assertThat(pledge.get("rewardTierId")).isEqualTo(rewardId.toString());
        assertThat(pledge.get("shippingCountry")).isEqualTo("AZ");

        // PL-01 the tier, PL-03 the bonus above it, PL-04 two add-ons, PL-05 the
        // postage on both lines -- the first unit at the rate and each one after it at
        // the additional rate -- and PL-06 the sum. Exact at two decimal places, which
        // is the arithmetic a double gets wrong and a card is charged for.
        assertThat(amount(pledge, "base")).isEqualTo("45.00");
        assertThat(amount(pledge, "addons")).isEqualTo("30.00");
        assertThat(amount(pledge, "bonus")).isEqualTo("5.00");
        assertThat(amount(pledge, "shipping")).isEqualTo("9.00");
        // Zero until #78, and deliberately present rather than omitted -- see TaxPolicy.
        assertThat(amount(pledge, "tax")).isEqualTo("0.00");
        assertThat(amount(pledge, "total")).isEqualTo("89.00");

        // The add-on lines and not only their sum: a total cannot be unpacked, and
        // the creator has to know what to put in the box. V18 creates the table.
        assertThat(addons(pledge)).containsExactly(Map.of("rewardTierId", addonId.toString(), "quantity", 2));

        // And the five columns are what the row holds, so the generated total above is
        // PostgreSQL's answer rather than one this response computed.
        assertThat(totalOf(id(pledge))).isEqualByComparingTo(new BigDecimal("89.00"));
        assertThat(reservedQuantity(rewardId)).isEqualTo(1);
        // Nothing is reserved on the add-on. Stated by the test because it is a
        // deliberate gap rather than an oversight -- see ReservationService.
        assertThat(reservedQuantity(addonId)).isZero();
    }

    @Test
    @DisplayName("a support-only pledge is priced at what the backer chose to give")
    void aPledgeWithoutARewardIsAllBase() {
        Account creator = account("creator");
        UUID projectId = project(creator);
        Campaigns.launch(dataSource, projectId);

        Map<String, Object> pledge =
                parse(post("/v1/pledges/draft", account("backer"), newKey(), draftBody(projectId, null, "25.00")));

        // §4.5's PL-02. The whole amount is the base and the bonus is nothing: a
        // support-only pledge read as a bonus on a reward nobody took would make every
        // "raised through rewards" report wrong.
        assertThat(pledge.get("rewardTierId")).isNull();
        assertThat(amount(pledge, "base")).isEqualTo("25.00");
        assertThat(amount(pledge, "bonus")).isEqualTo("0.00");
        assertThat(amount(pledge, "total")).isEqualTo("25.00");
    }

    @Test
    @DisplayName("a contribution below the reward's price is refused rather than rounded up")
    void aContributionBelowTheTierIsRefused() {
        Account creator = account("creator");
        UUID projectId = project(creator);
        UUID rewardId = reward(creator, projectId, "A boxed set", "45.00", tier -> {});
        Campaigns.launch(dataSource, projectId);

        ResponseEntity<String> refused =
                post("/v1/pledges/draft", account("backer"), newKey(), draftBody(projectId, rewardId, "40.00"));

        // Charging the tier's price anyway would take more than the number the backer
        // was looking at when they pressed the button.
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(code(refused)).isEqualTo("CONTRIBUTION_BELOW_REWARD_PRICE");
        assertThat(draftCount()).isZero();
    }

    @Test
    @DisplayName("a posted reward to a country nobody priced is refused")
    void anUnpricedDestinationIsRefused() {
        Account creator = account("creator");
        UUID projectId = project(creator);
        UUID rewardId = reward(creator, projectId, "A boxed set", "45.00", tier -> tier.put("shippingType", "DOMESTIC"));
        shipTo(creator, rewardId, "AZ", "5.00", "0.00");
        Campaigns.launch(dataSource, projectId);

        Map<String, Object> body = draftBody(projectId, rewardId, "45.00");
        body.put("shippingCountry", "GE");

        ResponseEntity<String> refused = post("/v1/pledges/draft", account("backer"), newKey(), body);

        // Quoting it at zero would make the creator pay the carrier out of their own
        // funding, and nobody would notice until the parcel was posted.
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(code(refused)).isEqualTo("SHIPPING_DESTINATION_UNPRICED");
        assertThat(meta(refused).get("shippingCountry")).isEqualTo("GE");
        // And the place was never taken: the selection is priced before anything is
        // held, so a quote that cannot be made costs nobody a place.
        assertThat(reservedQuantity(rewardId)).isZero();
    }

    // -----------------------------------------------------------------------
    // Idempotency
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("§10.3: a replayed key returns the original response, byte for byte")
    void aReplayedKeyReturnsTheOriginalResponse() {
        Account creator = account("creator");
        UUID projectId = project(creator);
        UUID rewardId = reward(creator, projectId, "A boxed set", "45.00", tier -> tier.put("limitQuantity", 5));
        Campaigns.launch(dataSource, projectId);

        Account backer = account("backer");
        String key = newKey();
        Map<String, Object> body = draftBody(projectId, rewardId, "45.00");

        ResponseEntity<String> first = post("/v1/pledges/draft", backer, key, body);
        ResponseEntity<String> replay = post("/v1/pledges/draft", backer, key, body);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        // The same status and the same bytes. Not a 409, and not a second pledge: a
        // client that retried because it never saw the first answer is entitled to
        // that answer.
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getBody()).isEqualTo(first.getBody());

        // Once, not twice. One pledge, and one place taken off the tier -- which is
        // the failure the header exists to prevent.
        assertThat(draftCount()).isEqualTo(1);
        assertThat(reservedQuantity(rewardId)).isEqualTo(1);
    }

    @Test
    @DisplayName("§10.4: the same key with a different body is a conflict")
    void aKeyReusedForADifferentRequestIsRefused() {
        Account creator = account("creator");
        UUID projectId = project(creator);
        Campaigns.launch(dataSource, projectId);

        Account backer = account("backer");
        String key = newKey();

        assertThat(post("/v1/pledges/draft", backer, key, draftBody(projectId, null, "25.00"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> reused = post("/v1/pledges/draft", backer, key, draftBody(projectId, null, "50.00"));

        // Neither answer would be safe. Replaying the first would tell the client a
        // fifty-manat pledge was made when a twenty-five-manat one was; executing the
        // second would make the key mean nothing.
        assertThat(reused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(reused)).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(draftCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("two spellings of one amount are one request, because the fingerprint is of the parsed body")
    void anEquivalentBodyIsTheSameRequest() {
        Account creator = account("creator");
        UUID projectId = project(creator);
        Campaigns.launch(dataSource, projectId);

        Account backer = account("backer");
        String key = newKey();

        ResponseEntity<String> first = post("/v1/pledges/draft", backer, key, draftBody(projectId, null, "25.00"));
        // "25" and "25.00" are the same money -- Money normalises both to two decimal
        // places on the way in -- so fingerprinting the parsed request rather than the
        // raw bytes is what stops a client's own formatting from reading as a
        // different intention.
        ResponseEntity<String> again = post("/v1/pledges/draft", backer, key, draftBody(projectId, null, "25"));

        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(again.getBody()).isEqualTo(first.getBody());
        assertThat(draftCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a missing Idempotency-Key is refused, not silently unprotected")
    void aMissingKeyIsRefused() {
        Account creator = account("creator");
        UUID projectId = project(creator);
        Campaigns.launch(dataSource, projectId);

        ResponseEntity<String> refused =
                post("/v1/pledges/draft", account("backer"), null, draftBody(projectId, null, "25.00"));

        // Treating an absent header as "this client does not want replay protection"
        // would make the guarantee opt-in for exactly the clients most likely to need
        // it.
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(code(refused)).isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
        assertThat(draftCount()).isZero();

        // And a key that is not a UUID is a different mistake with a different fix.
        ResponseEntity<String> malformed =
                post("/v1/pledges/draft", account("backer"), "not-a-uuid", draftBody(projectId, null, "25.00"));
        assertThat(malformed.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(code(malformed)).isEqualTo("IDEMPOTENCY_KEY_INVALID");
        assertThat(draftCount()).isZero();
    }

    @Test
    @DisplayName("one backer's key cannot replay another backer's request")
    void keysAreScopedToTheAccountThatSpentThem() {
        Account creator = account("creator");
        UUID projectId = project(creator);
        Campaigns.launch(dataSource, projectId);

        String key = newKey();
        ResponseEntity<String> mine = post("/v1/pledges/draft", account("backer"), key, draftBody(projectId, null, "25.00"));
        ResponseEntity<String> theirs =
                post("/v1/pledges/draft", account("backer"), key, draftBody(projectId, null, "25.00"));

        // Two accounts, one key, two pledges. Without the account in the unique index
        // the second caller would be handed the first one's pledge -- which names the
        // campaign, the amount, and the reward they chose.
        assertThat(mine.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(theirs.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(theirs.getBody()).isNotEqualTo(mine.getBody());
        assertThat(draftCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("two identical requests arriving at once produce exactly one pledge")
    void twoIdenticalRequestsAtOnceProduceOnePledge() throws InterruptedException {
        Account creator = account("creator");
        UUID projectId = project(creator);
        UUID rewardId = reward(creator, projectId, "A boxed set", "45.00", tier -> tier.put("limitQuantity", 5));
        Campaigns.launch(dataSource, projectId);

        Account backer = account("backer");
        String key = newKey();
        Map<String, Object> body = draftBody(projectId, rewardId, "45.00");

        // Real threads through the real endpoint against the real PostgreSQL. The
        // failure this is about only happens when both requests find no key at the
        // same instant, which no single-threaded test can produce.
        List<ResponseEntity<String>> answers = simultaneously(2, () -> post("/v1/pledges/draft", backer, key, body));

        // **One pledge, and one place.** The unique index decides which request
        // executes; nothing in Java could, because neither transaction can see the
        // other's intention before it is written down.
        assertThat(draftCount()).isEqualTo(1);
        assertThat(reservedQuantity(rewardId)).isEqualTo(1);

        // The winner is answered 201. The loser is either replayed with the winner's
        // response -- if the winner had committed by the time it looked -- or told the
        // first request is still running, which is a retry rather than a failure. Both
        // are correct and which one happens is a matter of microseconds, so the
        // assertion is that it is one of the two and never a second pledge.
        List<HttpStatus> statuses = answers.stream()
                .map(answer -> HttpStatus.valueOf(answer.getStatusCode().value()))
                .sorted()
                .toList();
        assertThat(statuses).first().isEqualTo(HttpStatus.CREATED);
        assertThat(statuses.get(1)).isIn(HttpStatus.CREATED, HttpStatus.CONFLICT);

        ResponseEntity<String> created =
                answers.stream().filter(a -> a.getStatusCode().value() == 201).findFirst().orElseThrow();
        for (ResponseEntity<String> answer : answers) {
            if (answer.getStatusCode().value() == 201) {
                // Every 201 is the same 201 -- the same bytes, not merely the same
                // pledge.
                assertThat(answer.getBody()).isEqualTo(created.getBody());
            } else {
                assertThat(code(answer)).isEqualTo("IDEMPOTENT_REQUEST_IN_PROGRESS");
            }
        }
    }

    @Test
    @DisplayName("a refused request gives its key back, so the client can retry with it")
    void aFailedRequestDoesNotBurnItsKey() {
        Account creator = account("creator");
        UUID projectId = project(creator);
        UUID soldOut = reward(creator, projectId, "The last one", "45.00", tier -> tier.put("limitQuantity", 1));
        Campaigns.launch(dataSource, projectId);

        Account first = account("backer");
        assertThat(post("/v1/pledges/draft", first, newKey(), draftBody(projectId, soldOut, "45.00"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        Account second = account("backer");
        String key = newKey();
        ResponseEntity<String> refused = post("/v1/pledges/draft", second, key, draftBody(projectId, soldOut, "45.00"));
        assertThat(code(refused)).isEqualTo("REWARD_SOLD_OUT");

        // The reason for a refusal can change between one request and the next -- here
        // the first backer's reservation lapses and the sweep gives the place back.
        // Recording the refusal would answer the same key with yesterday's "no" for
        // §17.2's whole day.
        jdbc().update("DELETE FROM pledges");
        jdbc().update("UPDATE reward_tiers SET reserved_quantity = 0 WHERE id = ?", soldOut);

        ResponseEntity<String> retried = post("/v1/pledges/draft", second, key, draftBody(projectId, soldOut, "45.00"));
        assertThat(retried.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("§17.2: keys are swept after twenty-four hours, a bounded batch at a time")
    void expiredKeysAreSwept() {
        Account creator = account("creator");
        UUID projectId = project(creator);
        Campaigns.launch(dataSource, projectId);

        clock.freeze();
        for (int i = 0; i < 3; i++) {
            assertThat(post("/v1/pledges/draft", account("backer"), newKey(), draftBody(projectId, null, "25.00"))
                            .getStatusCode())
                    .isEqualTo(HttpStatus.CREATED);
        }
        assertThat(keyCount()).isEqualTo(3);

        // Nothing has expired yet. A sweep that removed a key inside its retention
        // would turn a client's legitimate retry into a second pledge.
        assertThat(sweeper.removeExpiredKeys(clock.instant())).isZero();
        assertThat(keyCount()).isEqualTo(3);

        clock.advance(retention().plusSeconds(1));

        // Bounded: a day of traffic must not be one transaction, so the pass takes at
        // most a batch and comes back for the rest.
        assertThat(sweeper.removeExpiredKeys(clock.instant())).isEqualTo(2);
        assertThat(sweeper.removeExpiredKeys(clock.instant())).isEqualTo(1);
        assertThat(sweeper.removeExpiredKeys(clock.instant())).isZero();
        assertThat(keyCount()).isZero();

        // And the pledges the keys described are untouched. A payment record is not
        // swept because the note about a retry that can no longer happen was.
        assertThat(draftCount()).isEqualTo(3);
    }

    // -----------------------------------------------------------------------
    // What the campaign and the stock refuse
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("§10.4: a sold-out tier is refused with the tiers that are left")
    void aSoldOutTierOffersAlternatives() {
        Account creator = account("creator");
        UUID projectId = project(creator);
        UUID soldOut = reward(creator, projectId, "The last one", "45.00", tier -> tier.put("limitQuantity", 1));
        UUID roomLeft = reward(creator, projectId, "Still available", "20.00", tier -> {});
        UUID secret = reward(creator, projectId, "By invitation", "20.00", tier -> tier.put("isSecret", true));
        UUID addon = reward(creator, projectId, "An extra mug", "5.00", tier -> tier.put("isAddon", true));
        Campaigns.launch(dataSource, projectId);

        assertThat(post("/v1/pledges/draft", account("backer"), newKey(), draftBody(projectId, soldOut, "45.00"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> refused =
                post("/v1/pledges/draft", account("backer"), newKey(), draftBody(projectId, soldOut, "45.00"));

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(refused)).isEqualTo("REWARD_SOLD_OUT");
        assertThat(meta(refused).get("rewardTierId")).isEqualTo(soldOut.toString());

        // What a backer could actually select instead. A secret tier is reachable only
        // through its own link and listing it here would be the disclosure it exists to
        // prevent; an add-on is bought alongside a reward rather than instead of one.
        assertThat(alternatives(refused)).containsExactly(roomLeft.toString());
        assertThat(alternatives(refused)).doesNotContain(secret.toString(), addon.toString(), soldOut.toString());
    }

    @Test
    @DisplayName("§7.2: a backer gets one pledge per campaign")
    void aSecondPledgeByTheSameBackerIsRefused() {
        Account creator = account("creator");
        UUID projectId = project(creator);
        Campaigns.launch(dataSource, projectId);

        Account backer = account("backer");
        Map<String, Object> pledge =
                parse(post("/v1/pledges/draft", backer, newKey(), draftBody(projectId, null, "25.00")));

        // A different key, so this is a second intention and not a retry.
        ResponseEntity<String> refused = post("/v1/pledges/draft", backer, newKey(), draftBody(projectId, null, "25.00"));

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(refused)).isEqualTo("PLEDGE_ALREADY_EXISTS");
        // The pledge they already have, so the client opens it rather than starting a
        // second checkout.
        assertThat(meta(refused).get("pledgeId")).isEqualTo(id(pledge).toString());
        assertThat(meta(refused).get("state")).isEqualTo("DRAFT");
    }

    @Test
    @DisplayName("a campaign that is not taking pledges is refused, and one that never launched is a 404")
    void aCampaignThatIsNotLiveIsRefused() {
        Account creator = account("creator");
        UUID unlaunched = project(creator);

        // Never public. Answered as though it did not exist, because a refusal that
        // said "not live" would confirm to anybody holding an identifier that somebody
        // is preparing a campaign.
        ResponseEntity<String> hidden =
                post("/v1/pledges/draft", account("backer"), newKey(), draftBody(unlaunched, null, "25.00"));
        assertThat(hidden.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(code(hidden)).isEqualTo("PROJECT_NOT_FOUND");

        UUID projectId = project(creator);
        Campaigns.launch(dataSource, projectId);
        // Live, and past its deadline: §8.4's finalizer runs every minute and the
        // backer does not. A pledge taken in that minute would be a commitment made
        // after the funding window the backer was shown.
        //
        // The launch instant moves with it, because projects_deadline_follows_launch
        // refuses a campaign that closed before it opened -- which is the constraint
        // doing its job, and is why the row this writes is one the application could
        // have produced.
        jdbc().update(
                        """
                        UPDATE projects
                           SET launched_at = now() - interval '40 days',
                               deadline = now() - interval '1 hour'
                         WHERE id = ?
                        """,
                        projectId);

        ResponseEntity<String> refused =
                post("/v1/pledges/draft", account("backer"), newKey(), draftBody(projectId, null, "25.00"));

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(refused)).isEqualTo("PROJECT_NOT_LIVE");
        assertThat(meta(refused).get("state")).isEqualTo("LIVE");
        assertThat(draftCount()).isZero();
    }

    @Test
    @DisplayName("§17.3: a backer may draft ten pledges a minute and no more")
    void theCheckoutIsRateLimited() {
        Account creator = account("creator");
        UUID projectId = project(creator);
        Campaigns.launch(dataSource, projectId);

        Account backer = account("backer");
        int limit = properties.rateLimit().pledgesPerUser();

        // Every one of these is counted, including the ones refused for having a pledge
        // already: what the limit bounds is the work one caller can demand, and a
        // request that is refused after settling a stale pledge, reading a campaign and
        // locking a tier has already cost all of it.
        for (int attempt = 0; attempt < limit; attempt++) {
            post("/v1/pledges/draft", backer, newKey(), draftBody(projectId, null, "25.00"));
        }

        ResponseEntity<String> refused =
                post("/v1/pledges/draft", backer, newKey(), draftBody(projectId, null, "25.00"));
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        // A client that is told to wait can wait; without the header it retries
        // immediately and the limiter spends the window refusing.
        assertThat(refused.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNotNull();
    }

    // -----------------------------------------------------------------------
    // Reading one
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("a backer reads their own pledge, and somebody else's is a 404")
    void onlyTheBackerReadsTheirPledge() {
        Account creator = account("creator");
        UUID projectId = project(creator);
        UUID rewardId = reward(creator, projectId, "A boxed set", "45.00", tier -> {});
        Campaigns.launch(dataSource, projectId);

        Account backer = account("backer");
        UUID pledgeId = id(parse(post("/v1/pledges/draft", backer, newKey(), draftBody(projectId, rewardId, "45.00"))));

        Map<String, Object> read = parse(get("/v1/pledges/" + pledgeId, backer));
        assertThat(id(read)).isEqualTo(pledgeId);
        assertThat(amount(read, "total")).isEqualTo("45.00");

        // Not a 403. A pledge says who gave money and how much, and PL-12's anonymity
        // would mean very little if the pledge behind it could be confirmed to exist by
        // anybody holding its identifier.
        ResponseEntity<String> stranger = get("/v1/pledges/" + pledgeId, account("backer"));
        assertThat(stranger.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(code(stranger)).isEqualTo("PLEDGE_NOT_FOUND");
    }

    // -----------------------------------------------------------------------
    // Confirming
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("confirming moves the held place to a claimed one, in one statement")
    void confirmingMovesTheHeldPlaceToAClaimedOne() {
        Account creator = account("creator");
        UUID projectId = project(creator);
        UUID rewardId = reward(creator, projectId, "A boxed set", "45.00", tier -> tier.put("limitQuantity", 3));
        Campaigns.launch(dataSource, projectId);

        Account backer = account("backer");
        UUID pledgeId = id(parse(post("/v1/pledges/draft", backer, newKey(), draftBody(projectId, rewardId, "45.00"))));
        assertThat(reservedQuantity(rewardId)).isEqualTo(1);
        assertThat(claimedQuantity(rewardId)).isZero();

        UUID paymentMethodId = UUID.randomUUID();
        ResponseEntity<String> confirmed = post(
                "/v1/pledges/" + pledgeId + "/confirm",
                backer,
                newKey(),
                Map.of("paymentMethodId", paymentMethodId.toString()));

        assertThat(confirmed.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> pledge = parse(confirmed);
        assertThat(pledge.get("state")).isEqualTo("CONFIRMED");
        assertThat(pledge.get("confirmedAt")).isNotNull();
        // Null once it is not a draft. The column keeps its value -- it is a true
        // statement about the window the backer had -- but a confirmed pledge holds no
        // reservation, and a countdown on that screen has nothing to count down to.
        assertThat(pledge.get("reservationExpiresAt")).isNull();
        // Accepted and stored so the shape does not change when #55 lands. Nothing
        // resolves it.
        assertThat(pledge.get("paymentMethodId")).isEqualTo(paymentMethodId.toString());
        // **No card was verified and nothing was charged.** §9.2's phase 1 is #55,
        // blocked on #60, and §9.2 is explicit that no money moves here in any case.
        assertThat(pledge.get("cardVerified")).isEqualTo(false);

        // One place, moved. The sum never changes, which is what stops another checkout
        // from taking a place that has already been sold and stops V7's constraint from
        // refusing a legitimate confirmation of the last one.
        assertThat(reservedQuantity(rewardId)).isZero();
        assertThat(claimedQuantity(rewardId)).isEqualTo(1);
        assertThat(committedQuantity(rewardId)).isEqualTo(1);
    }

    @Test
    @DisplayName("a replayed confirmation returns the original response and commits one place")
    void aReplayedConfirmationCommitsOnce() {
        Account creator = account("creator");
        UUID projectId = project(creator);
        UUID rewardId = reward(creator, projectId, "A boxed set", "45.00", tier -> tier.put("limitQuantity", 3));
        Campaigns.launch(dataSource, projectId);

        Account backer = account("backer");
        UUID pledgeId = id(parse(post("/v1/pledges/draft", backer, newKey(), draftBody(projectId, rewardId, "45.00"))));

        String key = newKey();
        ResponseEntity<String> first = post("/v1/pledges/" + pledgeId + "/confirm", backer, key, Map.of());
        ResponseEntity<String> replay = post("/v1/pledges/" + pledgeId + "/confirm", backer, key, Map.of());

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getBody()).isEqualTo(first.getBody());

        // The replay is not a second transition, which is what the count proves: a
        // re-execution would have tried to commit a place the tier no longer holds.
        assertThat(claimedQuantity(rewardId)).isEqualTo(1);
        assertThat(reservedQuantity(rewardId)).isZero();

        // A *different* request asking for the same transition is a different matter:
        // §6.2 has no edge out of CONFIRMED for a backer, and answering it with a
        // success would say a transition happened when it did not.
        ResponseEntity<String> again = post("/v1/pledges/" + pledgeId + "/confirm", backer, newKey(), Map.of());
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(again)).isEqualTo("PLEDGE_NOT_DRAFT");
        assertThat(meta(again).get("state")).isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("a draft whose five minutes ran out cannot be confirmed")
    void confirmingAfterTheReservationLapsedIsRefused() {
        Account creator = account("creator");
        UUID projectId = project(creator);
        UUID rewardId = reward(creator, projectId, "A boxed set", "45.00", tier -> tier.put("limitQuantity", 3));
        Campaigns.launch(dataSource, projectId);

        clock.freeze();
        Account backer = account("backer");
        UUID pledgeId = id(parse(post("/v1/pledges/draft", backer, newKey(), draftBody(projectId, rewardId, "45.00"))));

        // Without the sweep having run: the clock decides, not the state. A draft whose
        // window closed forty seconds ago is still DRAFT in the table, and confirming it
        // would commit a place the tier has already promised to give back.
        clock.advance(properties.reservation().ttl().plusSeconds(1));

        ResponseEntity<String> refused = post("/v1/pledges/" + pledgeId + "/confirm", backer, newKey(), Map.of());

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(refused)).isEqualTo("RESERVATION_EXPIRED");
        assertThat(claimedQuantity(rewardId)).isZero();
        // Still held, and still a draft: the sweep releases it within the minute, and
        // doing it here would be a write rolled back by the refusal that caused it.
        assertThat(reservedQuantity(rewardId)).isEqualTo(1);
        assertThat(state(pledgeId)).isEqualTo("DRAFT");
    }

    @Test
    @DisplayName("confirming somebody else's pledge is a 404")
    void confirmingAStrangersPledgeIsRefused() {
        Account creator = account("creator");
        UUID projectId = project(creator);
        Campaigns.launch(dataSource, projectId);

        UUID pledgeId = id(parse(
                post("/v1/pledges/draft", account("backer"), newKey(), draftBody(projectId, null, "25.00"))));

        ResponseEntity<String> refused =
                post("/v1/pledges/" + pledgeId + "/confirm", account("backer"), newKey(), Map.of());

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(code(refused)).isEqualTo("PLEDGE_NOT_FOUND");
        assertThat(state(pledgeId)).isEqualTo("DRAFT");
    }

    // -----------------------------------------------------------------------
    // Running requests at the same time
    // -----------------------------------------------------------------------

    /**
     * Runs one request from several threads at once and collects what each was told.
     *
     * <p>The latch is what makes it a race. Submitting the tasks is not enough: a pool
     * that starts the first thread before the last one is queued would let the early
     * one finish before the late one begins, and the test would pass against an
     * implementation with no concurrency control at all.
     */
    private List<ResponseEntity<String>> simultaneously(int threads, Request request) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(threads);
        List<ResponseEntity<String>> answers = Collections.synchronizedList(new ArrayList<>());
        List<Throwable> unexpected = Collections.synchronizedList(new ArrayList<>());

        try {
            for (int i = 0; i < threads; i++) {
                pool.execute(() -> {
                    try {
                        start.await();
                        answers.add(request.send());
                    } catch (Throwable t) {
                        // Kept rather than logged: an exception swallowed inside a
                        // thread is a test that passes while the thing it is about is
                        // broken.
                        unexpected.add(t);
                    } finally {
                        finished.countDown();
                    }
                });
            }
            start.countDown();
            // Generous, because it is a deadlock detector rather than a performance
            // assertion.
            assertThat(finished.await(60, TimeUnit.SECONDS))
                    .withFailMessage("The requests did not finish; something is holding a lock")
                    .isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(unexpected).isEmpty();
        return List.copyOf(answers);
    }

    /** One HTTP call, so that {@link #simultaneously} can make several of them at once. */
    private interface Request {
        ResponseEntity<String> send();
    }

    // -----------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------

    /** A registered, signed-in account: its access token and its identifier. */
    private record Account(String accessToken, UUID id) {
    }

    private Account account(String role) {
        String marker = role + "-" + SEQUENCE.incrementAndGet();
        String email = marker + "@example.com";

        rest.postForEntity(
                "/v1/auth/register",
                Map.of("email", email, "password", PASSWORD, "name", "Test " + role),
                String.class);

        Map<String, Object> signedIn = parse(rest.exchange(
                "/v1/auth/login",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("email", email, "password", PASSWORD, "tokenDelivery", "body"), jsonHeaders()),
                String.class));

        UUID id = jdbc().queryForObject("SELECT id FROM users WHERE email = ?::citext", UUID.class, email);
        return new Account((String) signedIn.get("accessToken"), id);
    }

    private UUID project(Account creator) {
        return id(parse(post("/v1/projects", creator, null, Map.of("title", "A campaign"))));
    }

    /**
     * A reward tier, before the campaign launches.
     *
     * <p>Before, because §5.3 freezes a tier's price once the campaign is live and the
     * shipping table with it. A fixture that created tiers afterwards would be testing
     * the lock rather than the checkout.
     */
    private UUID reward(Account creator, UUID projectId, String title, String price, TierFields extra) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", title);
        body.put("price", Map.of("amount", price, "currency", "AZN"));
        extra.applyTo(body);
        return id(parse(post("/v1/projects/" + projectId + "/rewards", creator, null, body)));
    }

    /** Extra fields on a tier, so each test says only what it cares about. */
    private interface TierFields {
        void applyTo(Map<String, Object> body);
    }

    private void shipTo(Account creator, UUID rewardId, String country, String amount, String additional) {
        rest.exchange(
                "/v1/rewards/" + rewardId + "/shipping-rules",
                HttpMethod.PUT,
                new HttpEntity<>(
                        Map.of(
                                "rules",
                                List.of(Map.of(
                                        "countryCode", country,
                                        "amount", amount,
                                        "additionalItemAmount", additional))),
                        bearer(creator.accessToken())),
                String.class);
    }

    /** The smallest body {@code POST /v1/pledges/draft} accepts. */
    private static Map<String, Object> draftBody(UUID projectId, UUID rewardTierId, String contribution) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("projectId", projectId.toString());
        if (rewardTierId != null) {
            body.put("rewardTierId", rewardTierId.toString());
        }
        body.put("contribution", Map.of("amount", contribution, "currency", "AZN"));
        return body;
    }

    /** A fresh {@code Idempotency-Key}. §10.3 makes it a UUID. */
    private static String newKey() {
        return UUID.randomUUID().toString();
    }

    // -----------------------------------------------------------------------
    // HTTP
    // -----------------------------------------------------------------------

    private ResponseEntity<String> post(String path, Account account, String idempotencyKey, Object body) {
        HttpHeaders headers = bearer(account.accessToken());
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> get(String path, Account account) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(bearer(account.accessToken())), String.class);
    }

    private static HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private static HttpHeaders bearer(String accessToken) {
        HttpHeaders headers = jsonHeaders();
        headers.setBearerAuth(accessToken);
        return headers;
    }

    // -----------------------------------------------------------------------
    // Readings
    // -----------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(ResponseEntity<String> response) {
        try {
            return json.readValue(response.getBody(), Map.class);
        } catch (Exception e) {
            throw new IllegalStateException("Not a JSON object: " + response.getBody(), e);
        }
    }

    private static UUID id(Map<String, Object> resource) {
        return UUID.fromString((String) resource.get("id"));
    }

    /** One of the six amounts, as the string §10.3 requires it to be. */
    @SuppressWarnings("unchecked")
    private static String amount(Map<String, Object> pledge, String line) {
        Map<String, Object> amounts = (Map<String, Object>) pledge.get("amounts");
        return (String) ((Map<String, Object>) amounts.get(line)).get("amount");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> addons(Map<String, Object> pledge) {
        return (List<Map<String, Object>>) pledge.get("addons");
    }

    private String code(ResponseEntity<String> response) {
        return (String) parse(response).get("code");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> meta(ResponseEntity<String> response) {
        return (Map<String, Object>) parse(response).get("meta");
    }

    @SuppressWarnings("unchecked")
    private List<String> alternatives(ResponseEntity<String> response) {
        return (List<String>) meta(response).get("availableAlternatives");
    }

    /**
     * §17.2's twenty-four hours, read from the same properties the sweeper uses so
     * that the test cannot drift from the rule by carrying a number of its own.
     */
    private Duration retention() {
        return idempotency.retention();
    }

    private int reservedQuantity(UUID rewardTierId) {
        return count("SELECT reserved_quantity FROM reward_tiers WHERE id = ?", rewardTierId);
    }

    private int claimedQuantity(UUID rewardTierId) {
        return count("SELECT claimed_quantity FROM reward_tiers WHERE id = ?", rewardTierId);
    }

    private int committedQuantity(UUID rewardTierId) {
        return count("SELECT claimed_quantity + reserved_quantity FROM reward_tiers WHERE id = ?", rewardTierId);
    }

    private int count(String sql, UUID id) {
        Integer value = jdbc().queryForObject(sql, Integer.class, id);
        return value == null ? 0 : value;
    }

    private int draftCount() {
        Integer value = jdbc().queryForObject("SELECT count(*) FROM pledges", Integer.class);
        return value == null ? 0 : value;
    }

    private int keyCount() {
        Integer value = jdbc().queryForObject("SELECT count(*) FROM idempotency_keys", Integer.class);
        return value == null ? 0 : value;
    }

    private BigDecimal totalOf(UUID pledgeId) {
        return jdbc().queryForObject("SELECT total_amount FROM pledges WHERE id = ?", BigDecimal.class, pledgeId);
    }

    private String state(UUID pledgeId) {
        return jdbc().queryForObject("SELECT state FROM pledges WHERE id = ?", String.class, pledgeId);
    }
}
