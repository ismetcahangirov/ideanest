package az.ideanest.project;

import static org.assertj.core.api.Assertions.assertThat;

import az.ideanest.project.application.CampaignEndingSoonEvent;
import az.ideanest.project.application.DeadlineReminderJob;
import az.ideanest.support.AbstractIntegrationTest;
import az.ideanest.support.Campaigns;
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
 * §8.4's {@code deadline-reminder}: the half of §4.10's reminders that #39 left out.
 *
 * <p>What this suite is really about is the claim. The sweep's question — "which live campaigns
 * are within 48 hours of closing" — is true for the whole of a campaign's last two days, so
 * without {@code deadline_notices} the job would announce every closing campaign on every tick,
 * and every announcement is a message to every backer of it.
 *
 * <p>The tests that carry the design:
 *
 * <ul>
 *   <li>{@link #aCampaignIsAnnouncedOncePerThresholdHoweverOftenTheSweepRuns()} — the feature,
 *       and the failure it prevents.
 *   <li>{@link #aCampaignPastItsDeadlineIsNotAnnounced()} — the lower bound on the window. A
 *       campaign stays {@code LIVE} between its deadline and {@code campaign-finalizer}'s next
 *       pass, and "24 hours remaining" about a campaign that has closed is the one message this
 *       sweep must never send.
 *   <li>{@link #aCampaignThatStoppedBeingLiveIsNotAnnounced()} — the candidate list is a
 *       snapshot, and the announcing transaction re-checks it.
 *   <li>{@link #theEventCarriesTheThresholdRatherThanTheRemainder()} — a redelivery hours later
 *       still describes the message the platform decided to send.
 * </ul>
 */
class DeadlineReminderTests extends AbstractIntegrationTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @Autowired
    private DeadlineReminderJob job;

    @Autowired
    private DataSource dataSource;

    private String handle;
    private UUID creatorId;

    @BeforeEach
    void aCreator() {
        handle = "deadline-" + SEQUENCE.incrementAndGet();
        creatorId = Campaigns.creator(dataSource, handle);
    }

    @AfterEach
    void clear() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("DELETE FROM outbox_events");
        jdbc.update("DELETE FROM deadline_notices");
        jdbc.update("DELETE FROM project_state_transitions WHERE project_id IN (SELECT id FROM projects WHERE creator_id = ?)", creatorId);
        jdbc.update("DELETE FROM projects WHERE creator_id = ?", creatorId);
    }

    @Test
    @DisplayName("a campaign inside 48 hours is announced at the 48-hour threshold")
    void aCampaignInsideFortyEightHoursIsAnnounced() {
        UUID project = liveCampaignClosingIn(40);

        job.announceDueDeadlines();

        assertThat(thresholdsAnnouncedFor(project)).containsExactly(48);
        assertThat(eventsFor(project)).hasSize(1);
    }

    @Test
    @DisplayName("a campaign inside 24 hours crosses both thresholds in one pass, largest first")
    void aCampaignInsideTwentyFourHoursCrossesBoth() {
        UUID project = liveCampaignClosingIn(10);

        job.announceDueDeadlines();

        assertThat(thresholdsAnnouncedFor(project)).containsExactlyInAnyOrder(48, 24);
        assertThat(hoursRemainingInEventOrder(project))
                .as("largest first, so a reader receives them in the order they expect")
                .containsExactly(48, 24);
    }

    @Test
    @DisplayName("a campaign with days to run is not announced")
    void aCampaignWithDaysToRunIsNotAnnounced() {
        UUID project = liveCampaignClosingIn(24 * 10);

        job.announceDueDeadlines();

        assertThat(thresholdsAnnouncedFor(project)).isEmpty();
        assertThat(eventsFor(project)).isEmpty();
    }

    /**
     * The whole point of {@code deadline_notices}.
     *
     * <p>Without the claim, the second and third passes announce the campaign again — and would
     * carry on doing so once a minute until the deadline passed.
     */
    @Test
    @DisplayName("a campaign is announced once per threshold, however often the sweep runs")
    void aCampaignIsAnnouncedOncePerThresholdHoweverOftenTheSweepRuns() {
        UUID project = liveCampaignClosingIn(40);

        assertThat(job.announceDueDeadlines()).isEqualTo(1);
        assertThat(job.announceDueDeadlines()).as("the claim is what makes the second pass quiet").isZero();
        assertThat(job.announceDueDeadlines()).isZero();

        assertThat(eventsFor(project)).hasSize(1);
    }

    /**
     * The lower bound on the window.
     *
     * <p>A campaign is still {@code LIVE} between its deadline and the finaliser's next pass. A
     * sweep without {@code deadline > now} would use that gap to tell every backer that a
     * campaign which has just closed has 24 hours left.
     */
    @Test
    @DisplayName("a campaign past its deadline is not announced")
    void aCampaignPastItsDeadlineIsNotAnnounced() {
        UUID project = liveCampaignClosingIn(-1);

        job.announceDueDeadlines();

        assertThat(thresholdsAnnouncedFor(project)).isEmpty();
        assertThat(eventsFor(project)).isEmpty();
    }

    /**
     * The candidate list is a snapshot, and the announcing transaction re-checks it.
     *
     * <p>Driven by cancelling the campaign between the two, which is what the check defends
     * against: "48 hours remaining" arriving seconds after "this campaign has been cancelled".
     */
    @Test
    @DisplayName("a campaign that stopped being live between the read and the claim is not announced")
    void aCampaignThatStoppedBeingLiveIsNotAnnounced() {
        UUID project = liveCampaignClosingIn(40);
        new JdbcTemplate(dataSource).update("UPDATE projects SET state = 'CANCELED' WHERE id = ?", project);

        job.announceDueDeadlines();

        assertThat(eventsFor(project)).isEmpty();
    }

    /**
     * The threshold travels, not a computed remainder.
     *
     * <p>{@code CampaignEndingSoonEvent} argues it: an event redelivered six hours later still
     * says 48, so the message a backer reads is the one the platform decided to send rather than
     * a report of how long is left at the moment of delivery.
     */
    @Test
    @DisplayName("the event carries the threshold rather than the remainder")
    void theEventCarriesTheThresholdRatherThanTheRemainder() {
        UUID project = liveCampaignClosingIn(31);

        job.announceDueDeadlines();

        assertThat(hoursRemainingInEventOrder(project))
                .as("31 hours left, and the message says 48 because that is the threshold crossed")
                .containsExactly(48);
    }

    @Test
    @DisplayName("the event names the creator, so the audience can be resolved without reading projects")
    void theEventNamesTheCreator() {
        UUID project = liveCampaignClosingIn(40);

        job.announceDueDeadlines();

        assertThat(payloadsFor(project)).singleElement().asString().contains(creatorId.toString());
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    /**
     * A live campaign whose deadline is that many hours away. A negative value puts it in the
     * past, which is a campaign the finaliser has not reached yet.
     */
    private UUID liveCampaignClosingIn(int hours) {
        UUID project = Campaigns.seed(dataSource, creatorId, handle + "-" + SEQUENCE.incrementAndGet())
                .state("LIVE")
                .insert();
        new JdbcTemplate(dataSource)
                .update(
                        "UPDATE projects SET deadline = ?, launched_at = ? WHERE id = ?",
                        java.sql.Timestamp.from(Instant.now().plus(hours, ChronoUnit.HOURS)),
                        java.sql.Timestamp.from(Instant.now().minus(1, ChronoUnit.DAYS)),
                        project);
        return project;
    }

    private List<Integer> thresholdsAnnouncedFor(UUID project) {
        return new JdbcTemplate(dataSource)
                .queryForList(
                        "SELECT threshold_hours FROM deadline_notices WHERE project_id = ?", Integer.class, project);
    }

    private List<String> eventsFor(UUID project) {
        return new JdbcTemplate(dataSource)
                .queryForList(
                        "SELECT id::text FROM outbox_events WHERE aggregate_id = ? AND event_type = ?",
                        String.class,
                        project,
                        CampaignEndingSoonEvent.EVENT_TYPE);
    }

    private List<String> payloadsFor(UUID project) {
        return new JdbcTemplate(dataSource)
                .queryForList(
                        "SELECT payload::text FROM outbox_events WHERE aggregate_id = ? AND event_type = ?",
                        String.class,
                        project,
                        CampaignEndingSoonEvent.EVENT_TYPE);
    }

    /**
     * The thresholds announced, in the order the events were recorded.
     *
     * <p>Ordered by {@code sequence_no}, which is V19's own ordering column and the one the
     * relay dispatches by — an assertion about the order a reader receives messages in has to
     * use the order the platform actually publishes them in, not a timestamp two rows written in
     * the same transaction may share.
     */
    private List<Integer> hoursRemainingInEventOrder(UUID project) {
        return new JdbcTemplate(dataSource)
                .queryForList(
                        """
                        SELECT (payload::jsonb ->> 'hoursRemaining')::int
                          FROM outbox_events
                         WHERE aggregate_id = ? AND event_type = ?
                         ORDER BY sequence_no
                        """,
                        Integer.class,
                        project,
                        CampaignEndingSoonEvent.EVENT_TYPE);
    }
}
