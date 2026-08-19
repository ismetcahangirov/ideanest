package az.ideanest.notification;

import static org.assertj.core.api.Assertions.assertThat;

import az.ideanest.project.application.CampaignFinalizerJob;
import az.ideanest.shared.Identifiers;
import az.ideanest.shared.outbox.OutboxRelay;
import az.ideanest.support.AbstractIntegrationTest;
import az.ideanest.support.Campaigns;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A campaign closes, and everybody who is owed a message gets one.
 *
 * <p>The whole path, end to end and with no fixture standing in for any part of it: the
 * deadline passes, {@code campaign-finalizer} applies §5.1, the outbox event it records is
 * relayed, the notification module recognises the event type, resolves the audience
 * through {@code shared.audience}, and writes a row per recipient per channel.
 *
 * <p><strong>Driven this way deliberately.</strong> Recording the event by hand — which is
 * what {@code NotificationAudienceTests} does, correctly, because its subject is the
 * audience port — would leave the one thing #63 and #85 could still get wrong untested:
 * that the producer writes the event type the consumer switches on. Nothing in either
 * module fails to compile when those two strings disagree, and the symptom is a campaign
 * that closes and tells nobody.
 */
class CampaignOutcomeNotificationTests extends AbstractIntegrationTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    /** §4.10 gives both campaign outcomes email, push and in-app. */
    private static final int OUTCOME_CHANNELS = 3;

    private static final Duration CAMPAIGN_LENGTH = Duration.ofDays(30);

    @Autowired
    private CampaignFinalizerJob finalizer;

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private DataSource dataSource;

    private String handle;
    private UUID creatorId;

    @BeforeEach
    void aCreator() {
        handle = "outcome-" + SEQUENCE.incrementAndGet();
        creatorId = Campaigns.creator(dataSource, handle);
    }

    @AfterEach
    void clear() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("DELETE FROM notifications");
        jdbc.update("DELETE FROM outbox_events");
        jdbc.update("DELETE FROM pledges WHERE project_id IN (SELECT id FROM projects WHERE creator_id = ?)", creatorId);
        jdbc.update(
                "DELETE FROM project_state_transitions WHERE project_id IN"
                        + " (SELECT id FROM projects WHERE creator_id = ?)",
                creatorId);
        jdbc.update("DELETE FROM projects WHERE creator_id = ?", creatorId);
    }

    @Test
    @DisplayName("a campaign that funds tells the creator and every backer")
    void aFundedCampaignTellsEverybody() {
        UUID projectId = closed("10000.00", "12500.00", 2);
        UUID first = backer(projectId);
        UUID second = backer(projectId);

        close();

        assertThat(recipients("CAMPAIGN_SUCCEEDED")).containsExactlyInAnyOrder(creatorId, first, second);
        assertThat(rowCount("CAMPAIGN_SUCCEEDED")).isEqualTo(3 * OUTCOME_CHANNELS);
        assertThat(recipients("CAMPAIGN_UNSUCCESSFUL")).isEmpty();
    }

    /**
     * The backers of a failed campaign are told too, and it is not politeness.
     *
     * <p>They are holding a commitment that will never be charged and a stored card §5.1
     * has the platform delete within thirty days. Somebody who is not told waits for a
     * charge that never comes.
     */
    @Test
    @DisplayName("a campaign that does not fund tells the creator and every backer")
    void anUnsuccessfulCampaignTellsEverybody() {
        UUID projectId = closed("10000.00", "400.00", 1);
        UUID backer = backer(projectId);

        close();

        assertThat(recipients("CAMPAIGN_UNSUCCESSFUL")).containsExactlyInAnyOrder(creatorId, backer);
        assertThat(recipients("CAMPAIGN_SUCCEEDED")).isEmpty();
    }

    /**
     * The message can say what the campaign raised, and it says the frozen number.
     *
     * <p>The rendering document is what a template reads, so an absent {@code pledged}
     * would make "you raised 8,400 ₼ of your 10,000 ₼ goal" unwritable — the sentence a
     * creator whose campaign failed most needs to see. Money as §10.3's object with a
     * string amount, all the way into {@code notifications.params}.
     */
    @Test
    @DisplayName("the notification carries the frozen numbers, as money")
    void theNotificationCarriesTheOutcome() {
        closed("10000.00", "8400.00", 21);

        close();

        String params = new JdbcTemplate(dataSource)
                .queryForObject(
                        "SELECT params::text FROM notifications WHERE type = 'CAMPAIGN_UNSUCCESSFUL' LIMIT 1",
                        String.class);
        assertThat(params)
                .contains("\"amount\": \"8400.00\"")
                .contains("\"amount\": \"10000.00\"")
                .contains("\"backersCount\": 21");
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    /** Closes every campaign past its deadline and delivers what that produced. */
    private void close() {
        finalizer.finaliseClosedCampaigns(Instant.now().truncatedTo(ChronoUnit.MICROS));
        relay.run();
    }

    private UUID closed(String goal, String pledged, int backers) {
        Instant deadline = Instant.now().minus(Duration.ofDays(1));
        return Campaigns.seed(dataSource, creatorId, handle + "-" + SEQUENCE.incrementAndGet())
                .state("LIVE")
                .goal(goal)
                .pledged(pledged)
                .backers(backers)
                .launchedAt(deadline.minus(CAMPAIGN_LENGTH))
                .deadline(deadline)
                .insert();
    }

    /** A new account with a live commitment to this campaign. */
    private UUID backer(UUID projectId) {
        UUID backerId = Campaigns.creator(dataSource, handle + "-b" + SEQUENCE.incrementAndGet());
        new JdbcTemplate(dataSource)
                .update(
                        """
                        INSERT INTO pledges (id, project_id, backer_id, state, base_amount)
                        VALUES (?, ?, ?, 'CONFIRMED', 25.00)
                        """,
                        Identifiers.newIdentifier(),
                        projectId,
                        backerId);
        return backerId;
    }

    private List<UUID> recipients(String type) {
        return new JdbcTemplate(dataSource)
                .queryForList(
                        "SELECT DISTINCT recipient_id FROM notifications WHERE type = ?", UUID.class, type);
    }

    private long rowCount(String type) {
        Long count = new JdbcTemplate(dataSource)
                .queryForObject("SELECT count(*) FROM notifications WHERE type = ?", Long.class, type);
        return count == null ? 0 : count;
    }
}
