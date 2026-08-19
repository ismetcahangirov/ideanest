package az.ideanest.notification;

import static org.assertj.core.api.Assertions.assertThat;

import az.ideanest.notification.application.NotificationEvents.PledgeConfirmed;
import az.ideanest.notification.application.NotificationSender;
import az.ideanest.shared.EmailAddress;
import az.ideanest.shared.money.Money;
import az.ideanest.shared.outbox.Outbox;
import az.ideanest.shared.outbox.OutboxRelay;
import az.ideanest.support.AbstractIntegrationTest;
import az.ideanest.support.Campaigns;
import az.ideanest.user.infrastructure.UserRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The notification module over HTTP: an inbox somebody can read, and preferences somebody
 * can change.
 *
 * <p>#85 built the rows, the fan-out, the delivery loop, the read column and the preference
 * model, and exposed none of it. Everything asserted here was reachable only from Java
 * until this change, which is why #88 and #89 had nothing to call.
 *
 * <p>The ones that carry the design:
 *
 * <ul>
 *   <li>{@link #anInboxIsOnlyEverItsOwners()} — every query in {@code NotificationInbox} is
 *       keyed on the recipient, and that is the whole of the authorisation on this surface.
 *       If it were wrong, a bearer token would read anybody's notifications.
 *   <li>{@link #theInboxIsInAppOnly()} — an inbox that served email rows would be showing
 *       somebody a copy of their mail.
 *   <li>{@link #aPageContinuesFromItsCursorWithoutRepeatingOrSkipping()} — the reason the
 *       cursor is a pair. Two notifications from one event share an {@code occurred_at}, so
 *       an instant alone would serve one twice or drop the other, and the row that gets
 *       dropped is a message somebody was owed.
 *   <li>{@link #aStoredPreferenceIsWhatTheFanOutThenDoes()} — the endpoint and the fan-out
 *       reading one stored value the same way. A settings page that disagreed with delivery
 *       would look right and change nothing.
 *   <li>{@link #aRefusedSwitchSavesNothingElseInTheRequest()} — one button, one error,
 *       nothing half saved.
 * </ul>
 */
class NotificationApiTests extends AbstractIntegrationTest {

    /** Distinguishes the accounts these tests create. See {@code ReferralAttributionTests}. */
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private static final String PASSWORD = "a-long-enough-password";

    private static final String AGGREGATE = "pledge";

    /** §4.10 gives {@code PLEDGE_CONFIRMED} email, push and in-app. */
    private static final int PLEDGE_CONFIRMED_CHANNELS = 3;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private UserRepository users;

    @Autowired
    private Outbox outbox;

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private NotificationSender sender;

    @Autowired
    private PlatformTransactionManager transactions;

    @Autowired
    private DataSource dataSource;

    /**
     * A real campaign, because {@code pledge.confirmed} has more than one consumer.
     *
     * <p>The event these tests record is also consumed by {@code ReferralAttributionListener},
     * which writes a row referencing {@code projects}. An invented identifier therefore fails
     * that insert, which fails the whole dispatch — the property {@code NotificationFanOut}
     * spends a paragraph on, seen from the other side. A fixture that quietly avoided the
     * shared event would be testing a path production does not have.
     */
    private UUID projectId;

    @BeforeEach
    void aCampaignTheEventsCanRefer() {
        String handle = "notification-api-" + SEQUENCE.incrementAndGet();
        UUID creatorId = Campaigns.creator(dataSource, handle);
        projectId = Campaigns.seed(dataSource, creatorId, handle).state("LIVE").insert();
    }

    @AfterEach
    void clear() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("DELETE FROM notifications");
        jdbc.update("DELETE FROM notification_preferences");
        jdbc.update("DELETE FROM outbox_events");
        jdbc.update("DELETE FROM referral_attributions");
        jdbc.update("DELETE FROM project_state_transitions WHERE project_id = ?", projectId);
        jdbc.update("DELETE FROM projects WHERE id = ?", projectId);
    }

    // ------------------------------------------------------------------
    // The inbox
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the inbox serves this account's notifications, newest first, with the unread count")
    void theInboxIsServedNewestFirst() {
        Account backer = account("inbox");
        Instant older = notify(backer, minutesAgo(10));
        Instant newer = notify(backer, minutesAgo(1));
        deliver();

        ResponseEntity<Map<String, Object>> response = get("/v1/me/notifications", backer.accessToken());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(occurredAtsOf(response))
                .as("ordered by when the reported thing happened, newest first")
                .containsExactly(newer, older);
        assertThat(response.getBody().get("unreadCount")).isEqualTo(2);
        assertThat(response.getBody().get("nextCursor")).as("both rows fit on one page").isNull();

        Map<String, Object> first = notificationsOf(response).get(0);
        assertThat(first.get("type")).isEqualTo("PLEDGE_CONFIRMED");
        assertThat(first.get("category")).isEqualTo("PLEDGES");
        assertThat(first.get("subjectType")).isEqualTo(AGGREGATE);
        assertThat(first.get("readAt")).as("nulls are written out rather than omitted").isNull();
    }

    /**
     * The rendering document arrives as an object, with money as a string.
     *
     * <p>§10.3's rule, and the reason {@code NotificationResponse} emits {@code params} raw:
     * an amount that came back as a JSON number would be one that had been through a
     * decoder, and on a funding platform that is somebody's pledge.
     */
    @Test
    @DisplayName("the rendering document comes back as an object, with money as a string")
    void theRenderingDocumentSurvivesTheRoundTrip() {
        Account backer = account("params");
        notify(backer, minutesAgo(1));
        deliver();

        Map<String, Object> notification = notificationsOf(get("/v1/me/notifications", backer.accessToken()))
                .get(0);

        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) notification.get("params");
        @SuppressWarnings("unchecked")
        Map<String, Object> total = (Map<String, Object>) params.get("total");
        assertThat(total.get("amount")).isEqualTo("50.00");
        assertThat(total.get("currency")).isEqualTo("AZN");
    }

    /**
     * The whole of the authorisation on this surface.
     *
     * <p>Every query in {@code NotificationInbox} names the recipient. If one of them did
     * not, a bearer token would be a way to read anybody's notifications — and a
     * notification's contents are one person's business (§17.4).
     */
    @Test
    @DisplayName("an inbox holds only its owner's notifications")
    void anInboxIsOnlyEverItsOwners() {
        Account mine = account("mine");
        Account theirs = account("theirs");
        notify(mine, minutesAgo(1));
        notify(theirs, minutesAgo(1));
        deliver();

        assertThat(notificationsOf(get("/v1/me/notifications", mine.accessToken())))
                .hasSize(1);
        assertThat(notificationsOf(get("/v1/me/notifications", theirs.accessToken())))
                .hasSize(1);
        assertThat(notificationIdOf(mine)).isNotEqualTo(notificationIdOf(theirs));
    }

    /**
     * One event writes three rows and exactly one of them is an inbox row.
     *
     * <p>{@code notifications_only_the_inbox_is_read} means the read stamp does not even
     * exist on the other two channels, so an inbox that served them would be offering a
     * control the schema refuses.
     */
    @Test
    @DisplayName("the inbox is in-app only, even though one event writes three rows")
    void theInboxIsInAppOnly() {
        Account backer = account("channels");
        notify(backer, minutesAgo(1));
        deliver();

        assertThat(countOf("SELECT count(*) FROM notifications"))
                .as("§4.10 gives PLEDGE_CONFIRMED three channels")
                .isEqualTo(PLEDGE_CONFIRMED_CHANNELS);
        assertThat(notificationsOf(get("/v1/me/notifications", backer.accessToken())))
                .as("one of them is the inbox")
                .hasSize(1);
    }

    /**
     * A pending row is not in the inbox, which is the delay {@code InAppChannelSender}
     * argues is worth the uniformity.
     */
    @Test
    @DisplayName("a notification the sender has not reached yet is not in the inbox")
    void anUndeliveredNotificationIsNotInTheInbox() {
        Account backer = account("pending");
        notify(backer, minutesAgo(1));

        assertThat(notificationsOf(get("/v1/me/notifications", backer.accessToken())))
                .as("written by the fan-out, not yet handed to a channel")
                .isEmpty();
    }

    /**
     * The reason the cursor is {@code (occurredAt, id)} and not just an instant.
     *
     * <p>Three notifications read one at a time. If the cursor were the instant alone, two
     * rows sharing one would either both come back on the second page or neither would —
     * and here they do not share one, so what this actually proves is that the pair is
     * followed correctly at all. The pair is what makes it stay correct when they do.
     */
    @Test
    @DisplayName("a page continues from its cursor without repeating or skipping")
    void aPageContinuesFromItsCursorWithoutRepeatingOrSkipping() {
        Account backer = account("paging");
        notify(backer, minutesAgo(30));
        notify(backer, minutesAgo(20));
        notify(backer, minutesAgo(10));
        deliver();

        List<UUID> seen = new ArrayList<>();
        String path = "/v1/me/notifications?limit=1";
        for (int page = 0; page < 3; page++) {
            ResponseEntity<Map<String, Object>> response = get(path, backer.accessToken());
            List<Map<String, Object>> rows = notificationsOf(response);
            assertThat(rows).as("page %s", page).hasSize(1);
            seen.add(UUID.fromString((String) rows.get(0).get("id")));

            if (page < 2) {
                assertThat(response.getBody().get("nextCursor")).as("there is more").isNotNull();
                path = "/v1/me/notifications?limit=1&before=" + response.getBody().get("nextCursor")
                        + "&beforeId=" + response.getBody().get("nextCursorId");
            } else {
                assertThat(response.getBody().get("nextCursor"))
                        .as("the last page says so rather than counting what remains")
                        .isNull();
            }
        }

        assertThat(seen).as("three notifications, each seen once").hasSize(3).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("half a cursor is refused, naming the half that is missing")
    void halfACursorIsRefused() {
        Account backer = account("cursor");

        ResponseEntity<Map<String, Object>> response =
                get("/v1/me/notifications?before=" + Instant.now(), backer.accessToken());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("INBOX_QUERY_INVALID");
        assertThat(metaOf(response).get("field")).isEqualTo("beforeId");
    }

    /**
     * Refused rather than clamped, and the ceiling comes back with the refusal.
     *
     * <p>{@code InboxQueryInvalidException} argues it: a client that asked for a thousand
     * and silently received a hundred goes on believing it read the whole inbox.
     */
    @Test
    @DisplayName("a page size over the ceiling is refused, and the refusal carries the ceiling")
    void aPageSizeOverTheCeilingIsRefused() {
        Account backer = account("limit");

        ResponseEntity<Map<String, Object>> response =
                get("/v1/me/notifications?limit=1000", backer.accessToken());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("INBOX_QUERY_INVALID");
        assertThat(metaOf(response).get("field")).isEqualTo("limit");
        assertThat(metaOf(response).get("maxLimit")).isNotNull();
    }

    @Test
    @DisplayName("an inbox needs a token")
    void anInboxNeedsAToken() {
        assertThat(get("/v1/me/notifications", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------

    /**
     * Idempotent, and the first instant is the one kept.
     *
     * <p>{@code Notification.markRead} is where that lives, because a client re-rendering a
     * list must not be able to move the stamp to whenever it last drew the row — which is
     * not when anybody read anything.
     */
    @Test
    @DisplayName("marking a notification read is idempotent and keeps the first instant")
    void markingReadIsIdempotent() {
        Account backer = account("read");
        notify(backer, minutesAgo(1));
        deliver();
        UUID notificationId = notificationIdOf(backer);

        ResponseEntity<Map<String, Object>> first = post(read(notificationId), backer.accessToken());
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        Object readAt = first.getBody().get("readAt");
        assertThat(readAt).as("200 with the row, so the client has something to re-render").isNotNull();

        ResponseEntity<Map<String, Object>> again = post(read(notificationId), backer.accessToken());
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(again.getBody().get("readAt"))
                .as("a second call changes nothing, so a double tap and a retry are both harmless")
                .isEqualTo(readAt);

        assertThat(get("/v1/me/notifications", backer.accessToken()).getBody().get("unreadCount"))
                .isEqualTo(0);
    }

    /**
     * "Not yours" and "does not exist" are one answer.
     *
     * <p>{@code NotificationNotFoundException} says why: an endpoint that distinguished them
     * would let anybody holding a token confirm that an identifier is somebody's
     * notification.
     */
    @Test
    @DisplayName("marking somebody else's notification read is a 404, not a 403")
    void readingSomebodyElsesNotificationIsNotFound() {
        Account mine = account("reader");
        Account theirs = account("owner");
        notify(theirs, minutesAgo(1));
        deliver();

        ResponseEntity<Map<String, Object>> response = post(read(notificationIdOf(theirs)), mine.accessToken());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code")).isEqualTo("NOTIFICATION_NOT_FOUND");
    }

    /**
     * An email row cannot be read, and says so as a 404.
     *
     * <p>{@code notifications_only_the_inbox_is_read} would refuse the write anyway. What
     * this checks is that the refusal is the module's answer rather than a constraint
     * violation, and that it does not confirm which channel the notification went out on.
     */
    @Test
    @DisplayName("a notification that is not an inbox row cannot be marked read")
    void onlyAnInboxRowCanBeMarkedRead() {
        Account backer = account("email");
        notify(backer, minutesAgo(1));
        deliver();
        UUID emailRow = new JdbcTemplate(dataSource)
                .queryForObject(
                        "SELECT id FROM notifications WHERE recipient_id = ? AND channel = 'EMAIL'",
                        UUID.class,
                        backer.id());

        ResponseEntity<Map<String, Object>> response = post(read(emailRow), backer.accessToken());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code")).isEqualTo("NOTIFICATION_NOT_FOUND");
    }

    // ------------------------------------------------------------------
    // Preferences
    // ------------------------------------------------------------------

    /**
     * A settings page for an account with no rows at all, which is the common case.
     *
     * <p>{@code DeliveryPolicy} explains why nothing is seeded at registration. A response
     * that listed the table would be empty here, and a client would have to reimplement the
     * defaults to draw anything.
     */
    @Test
    @DisplayName("the settings page lists every switch resolved, before anybody has said anything")
    void theSettingsPageIsCompleteBeforeAnythingIsStored() {
        Account person = account("prefs");

        ResponseEntity<Map<String, Object>> response =
                get("/v1/me/notification-preferences", person.accessToken());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(countOf("SELECT count(*) FROM notification_preferences")).isZero();

        List<Map<String, Object>> preferences = preferencesOf(response);
        assertThat(preferences).as("seven categories, three channels each").hasSize(21);
        assertThat(preferences).allSatisfy(preference -> {
            assertThat(preference.get("mode")).as("§4.10's table describes what people are told").isEqualTo("IMMEDIATE");
            assertThat(preference.get("stored")).as("nobody has said anything").isEqualTo(false);
        });

        Map<String, Object> security = switchFor(preferences, "SECURITY", "EMAIL");
        assertThat(security.get("changeable"))
                .as("the switch is drawn disabled rather than left out")
                .isEqualTo(false);
        assertThat(security.get("digestOffered")).isEqualTo(false);
        assertThat(switchFor(preferences, "PLEDGES", "IN_APP").get("digestOffered"))
                .as("an inbox is already a list")
                .isEqualTo(false);
        assertThat(switchFor(preferences, "PLEDGES", "EMAIL").get("digestOffered")).isEqualTo(true);
    }

    /**
     * The endpoint and the fan-out reading one stored value the same way.
     *
     * <p>This is the test that would catch the worst possible disagreement in this module:
     * a settings page showing a switch off while the fan-out reads it as on, or the other
     * way round. Both sides go through {@code DeliveryPolicy} precisely so that this holds.
     */
    @Test
    @DisplayName("a stored preference is what the fan-out then does")
    void aStoredPreferenceIsWhatTheFanOutThenDoes() {
        Account backer = account("honoured");

        ResponseEntity<Map<String, Object>> saved = patch(
                "/v1/me/notification-preferences",
                backer.accessToken(),
                Map.of("preferences", List.of(Map.of("category", "PLEDGES", "channel", "IN_APP", "mode", "OFF"))));

        assertThat(saved.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> inApp = switchFor(preferencesOf(saved), "PLEDGES", "IN_APP");
        assertThat(inApp.get("mode")).isEqualTo("OFF");
        assertThat(inApp.get("stored")).as("this is a thing the person said, not a default").isEqualTo(true);
        assertThat(preferencesOf(saved)).as("the whole page comes back, not just what changed").hasSize(21);

        notify(backer, minutesAgo(1));
        deliver();

        assertThat(countOf("SELECT count(*) FROM notifications WHERE channel = 'IN_APP'"))
                .as("OFF is honoured by absence — not by a suppressed row")
                .isZero();
        assertThat(notificationsOf(get("/v1/me/notifications", backer.accessToken())))
                .isEmpty();
    }

    /**
     * A digest of an inbox would combine a list into a list.
     *
     * <p>Refused here rather than clamped, which is the opposite of what
     * {@code DeliveryPolicy} does with a value that is already stored —
     * {@code DeliveryModeUnavailableException} argues why both answers are right.
     */
    @Test
    @DisplayName("a digest on the in-app channel is refused, naming the pair")
    void aDigestOnTheInboxIsRefused() {
        Account person = account("digest");

        ResponseEntity<Map<String, Object>> response = patch(
                "/v1/me/notification-preferences",
                person.accessToken(),
                Map.of("preferences", List.of(Map.of("category", "PLEDGES", "channel", "IN_APP", "mode", "DIGEST"))));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(response.getBody().get("code")).isEqualTo("DELIVERY_MODE_UNAVAILABLE");
        assertThat(metaOf(response).get("channel")).isEqualTo("IN_APP");
        assertThat(metaOf(response).get("mode")).isEqualTo("DIGEST");
        assertThat(countOf("SELECT count(*) FROM notification_preferences")).isZero();
    }

    /**
     * The person who would want a security alert silenced is the one who stole the account.
     *
     * <p>Refused rather than accepted and ignored: the instruction is unstorable in effect
     * either way, and the difference is whether the person is told.
     */
    @Test
    @DisplayName("a security preference is refused whatever mode it asks for")
    void aSecurityPreferenceIsRefused() {
        Account person = account("security");

        for (String mode : List.of("OFF", "IMMEDIATE", "DIGEST")) {
            ResponseEntity<Map<String, Object>> response = patch(
                    "/v1/me/notification-preferences",
                    person.accessToken(),
                    Map.of("preferences", List.of(Map.of("category", "SECURITY", "channel", "EMAIL", "mode", mode))));

            assertThat(response.getStatusCode())
                    .as("SECURITY is not the caller's to change, whatever they are asking for")
                    .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(response.getBody().get("code")).isEqualTo("PREFERENCE_NOT_CHANGEABLE");
            assertThat(metaOf(response).get("category")).isEqualTo("SECURITY");
        }
        assertThat(countOf("SELECT count(*) FROM notification_preferences")).isZero();
    }

    /**
     * One button, one error, nothing half saved.
     *
     * <p>{@code NotificationPreferences.apply} checks every instruction before it writes any.
     * Without that, a person who pressed save once and saw one error would have no way to
     * know that part of their change had landed.
     */
    @Test
    @DisplayName("a refused switch saves nothing else in the same request")
    void aRefusedSwitchSavesNothingElseInTheRequest() {
        Account person = account("atomic");

        ResponseEntity<Map<String, Object>> response = patch(
                "/v1/me/notification-preferences",
                person.accessToken(),
                Map.of(
                        "preferences",
                        List.of(
                                Map.of("category", "PLEDGES", "channel", "EMAIL", "mode", "OFF"),
                                Map.of("category", "SECURITY", "channel", "EMAIL", "mode", "OFF"))));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(countOf("SELECT count(*) FROM notification_preferences"))
                .as("the valid switch in front of the refused one is not saved either")
                .isZero();
    }

    @Test
    @DisplayName("an empty change list is accepted and answers with the page")
    void anEmptyChangeListIsARead() {
        Account person = account("empty");

        ResponseEntity<Map<String, Object>> response = patch(
                "/v1/me/notification-preferences", person.accessToken(), Map.of("preferences", List.of()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(preferencesOf(response)).hasSize(21);
        assertThat(countOf("SELECT count(*) FROM notification_preferences")).isZero();
    }

    @Test
    @DisplayName("a preference is set once and then changed, and the row is reused")
    void aPreferenceIsReplacedRatherThanDuplicated() {
        Account person = account("change");

        patch(
                "/v1/me/notification-preferences",
                person.accessToken(),
                Map.of("preferences", List.of(Map.of("category", "PLEDGES", "channel", "EMAIL", "mode", "OFF"))));
        ResponseEntity<Map<String, Object>> changed = patch(
                "/v1/me/notification-preferences",
                person.accessToken(),
                Map.of("preferences", List.of(Map.of("category", "PLEDGES", "channel", "EMAIL", "mode", "DIGEST"))));

        assertThat(changed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(switchFor(preferencesOf(changed), "PLEDGES", "EMAIL").get("mode")).isEqualTo("DIGEST");
        assertThat(countOf("SELECT count(*) FROM notification_preferences"))
                .as("notification_preferences_key is UNIQUE (user_id, category, channel)")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the settings page needs a token")
    void theSettingsPageNeedsAToken() {
        assertThat(get("/v1/me/notification-preferences", null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private record Account(String accessToken, UUID id) {
    }

    private Account account(String prefix) {
        EmailAddress email = EmailAddress.of(prefix + SEQUENCE.incrementAndGet() + "@example.com");
        rest.postForEntity(
                "/v1/auth/register",
                Map.of("email", email.value(), "password", PASSWORD, "name", "Test Person"),
                String.class);

        ResponseEntity<Map<String, Object>> signedIn = rest.exchange(
                "/v1/auth/login",
                HttpMethod.POST,
                new HttpEntity<>(
                        Map.of("email", email.value(), "password", PASSWORD, "tokenDelivery", "body"), jsonHeaders()),
                new ParameterizedTypeReference<Map<String, Object>>() {});

        UUID id = users.findByEmailAndDeletedAtIsNull(email).orElseThrow().getId();
        return new Account((String) signedIn.getBody().get("accessToken"), id);
    }

    /**
     * Records a {@code pledge.confirmed} for this account and fans it out.
     *
     * <p>Through the outbox and the relay rather than by inserting rows, so that what is
     * being read back is what the module actually writes — a fixture that built its own
     * notifications could disagree with the fan-out and every assertion here would still
     * pass.
     *
     * @return the instant the notification reports, which is what the inbox is ordered by
     */
    private Instant notify(Account recipient, Instant occurredAt) {
        UUID pledgeId = UUID.randomUUID();
        PledgeConfirmed event = new PledgeConfirmed(
                pledgeId, projectId, recipient.id(), Money.of(new BigDecimal("50.00"), "AZN"), occurredAt);
        new TransactionTemplate(transactions)
                .executeWithoutResult(status -> outbox.record(AGGREGATE, pledgeId, PledgeConfirmed.EVENT_TYPE, event));
        relay.run();
        return occurredAt;
    }

    /**
     * Drains the queue, so that everything written is {@code SENT}.
     *
     * <p>A loop because the test profile bounds a pass at two rows, and this suite is not
     * about that bound — {@code NotificationDeliveryTests} owns it.
     */
    private void deliver() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        while (sender.sendPending(now) > 0) {
            // Until the queue stops yielding.
        }
    }

    private static Instant minutesAgo(int minutes) {
        return Instant.now().minus(Duration.ofMinutes(minutes)).truncatedTo(ChronoUnit.MICROS);
    }

    private UUID notificationIdOf(Account account) {
        return new JdbcTemplate(dataSource)
                .queryForObject(
                        "SELECT id FROM notifications WHERE recipient_id = ? AND channel = 'IN_APP'",
                        UUID.class,
                        account.id());
    }

    private long countOf(String sql) {
        return new JdbcTemplate(dataSource).queryForObject(sql, Long.class);
    }

    private static String read(UUID notificationId) {
        return "/v1/me/notifications/" + notificationId + "/read";
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> notificationsOf(ResponseEntity<Map<String, Object>> response) {
        return (List<Map<String, Object>>) response.getBody().get("notifications");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> preferencesOf(ResponseEntity<Map<String, Object>> response) {
        return (List<Map<String, Object>>) response.getBody().get("preferences");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> metaOf(ResponseEntity<Map<String, Object>> response) {
        return (Map<String, Object>) response.getBody().get("meta");
    }

    private static Map<String, Object> switchFor(
            List<Map<String, Object>> preferences, String category, String channel) {
        return preferences.stream()
                .filter(preference -> category.equals(preference.get("category"))
                        && channel.equals(preference.get("channel")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No switch for " + category + " on " + channel));
    }

    private static List<Instant> occurredAtsOf(ResponseEntity<Map<String, Object>> response) {
        return notificationsOf(response).stream()
                .map(notification -> Instant.parse((String) notification.get("occurredAt")))
                .toList();
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

    private ResponseEntity<Map<String, Object>> exchange(String path, HttpMethod method, String token, Object body) {
        return rest.exchange(
                path,
                method,
                new HttpEntity<>(body, token == null ? jsonHeaders() : bearer(token)),
                new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    private ResponseEntity<Map<String, Object>> get(String path, String token) {
        return exchange(path, HttpMethod.GET, token, null);
    }

    private ResponseEntity<Map<String, Object>> post(String path, String token) {
        return exchange(path, HttpMethod.POST, token, null);
    }

    private ResponseEntity<Map<String, Object>> patch(String path, String token, Object body) {
        return exchange(path, HttpMethod.PATCH, token, body);
    }
}
