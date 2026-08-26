package az.ideanest.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import az.ideanest.shared.cache.CacheInvalidationListener;
import az.ideanest.shared.cache.CacheInvalidationProperties;
import az.ideanest.shared.cache.CacheInvalidator;
import az.ideanest.shared.cache.CacheTags;
import az.ideanest.shared.outbox.OutboxMessage;
import az.ideanest.shared.project.ProjectSummaries;
import az.ideanest.shared.project.ProjectSummary;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Which published events tell the web client to drop a cached page — issue #127.
 *
 * <p>A plain unit test, because every decision worth checking here is a translation: this event
 * means these tags, that one means nothing. What it is really guarding is a contract with
 * another repository's module — {@code apps/web/src/lib/cache/tags.ts} — which is duplicated on
 * purpose and can therefore drift.
 *
 * <p>WHAT THESE COVER:
 *
 * <ul>
 *   <li><strong>a campaign is named twice and both names are needed.</strong> The public page is
 *       read by address and everything hanging off it by identifier, so a listener that emitted
 *       one of them would refresh half a campaign.
 *   <li><strong>the feed is invalidated by membership and never by ordering.</strong> A pledge
 *       moves a campaign up the feed; evicting every feed page for that is a cache that is
 *       empty at any interesting traffic level.
 *   <li><strong>a campaign that cannot be summarised still invalidates what it can.</strong>
 *       Half an answer is better than none, and better than a tag with {@code null} in it.
 *   <li><strong>nothing throws.</strong> This runs inside the outbox relay's dispatch
 *       transaction, where an exception re-delivers an event whose real consumers already
 *       succeeded.
 * </ul>
 */
class CacheInvalidationTests {

    private static final UUID PROJECT = UUID.fromString("0193f2a1-0000-7000-8000-000000000001");

    private final ObjectMapper json = new ObjectMapper();

    private ProjectSummaries campaigns;
    private RecordingInvalidator invalidator;
    private CacheInvalidationListener listener;

    @BeforeEach
    void setUp() {
        campaigns = mock(ProjectSummaries.class);
        when(campaigns.summaryOf(any()))
                .thenReturn(Optional.of(new ProjectSummary(PROJECT, "A studio", "studio", "ayan", UUID.randomUUID())));

        invalidator = new RecordingInvalidator();
        listener = new CacheInvalidationListener(invalidator, campaigns, json);
    }

    @Test
    @DisplayName("a confirmed pledge names the campaign by identifier and by address")
    void pledgeConfirmedNamesBoth() {
        listener.on(message("pledge", UUID.randomUUID(), "pledge.confirmed", "{\"projectId\":\"" + PROJECT + "\"}"));

        assertThat(invalidator.tags).containsExactly("project:" + PROJECT, "campaign:ayan/studio");
    }

    @Test
    @DisplayName("a comment reads the campaign off the aggregate, because that is what it is keyed by")
    void commentReadsTheAggregate() {
        listener.on(message("project", PROJECT, "comment.posted", "{\"commentId\":\"" + UUID.randomUUID() + "\"}"));

        assertThat(invalidator.tags).contains("project:" + PROJECT);
    }

    @Test
    @DisplayName("a published update invalidates the campaign, which is the event's only reason to exist")
    void updatePublishedInvalidatesTheCampaign() {
        listener.on(message("project", PROJECT, "project.update_published", "{\"number\":3}"));

        assertThat(invalidator.tags).containsExactly("project:" + PROJECT, "campaign:ayan/studio");
    }

    /**
     * The distinction the whole design turns on: what is in the feed versus what order it is in.
     */
    @Test
    @DisplayName("a launch drops the feed and a pledge does not")
    void onlyMembershipDropsTheFeed() {
        listener.on(message("project", PROJECT, "project.launched", "{}"));
        assertThat(invalidator.tags).contains(CacheTags.DISCOVERY);

        invalidator.tags.clear();
        listener.on(message("pledge", UUID.randomUUID(), "pledge.confirmed", "{\"projectId\":\"" + PROJECT + "\"}"));
        assertThat(invalidator.tags).doesNotContain(CacheTags.DISCOVERY);
    }

    @Test
    @DisplayName("an event this cache does not care about is ignored without a lookup")
    void unrelatedEventsAreIgnored() {
        listener.on(message("pledge", UUID.randomUUID(), "pledge.edited", "{\"projectId\":\"" + PROJECT + "\"}"));
        listener.on(message("project", PROJECT, "project.approved", "{}"));

        assertThat(invalidator.tags).isEmpty();
        verify(campaigns, never()).summaryOf(any());
    }

    @Test
    @DisplayName("a campaign with no public path still invalidates what it can name")
    void halfAnAnswerIsBetterThanNone() {
        when(campaigns.summaryOf(any()))
                .thenReturn(Optional.of(new ProjectSummary(PROJECT, "A studio", null, null, UUID.randomUUID())));

        listener.on(message("project", PROJECT, "project.update_published", "{}"));

        assertThat(invalidator.tags).containsExactly("project:" + PROJECT);
    }

    @Test
    @DisplayName("an unreadable payload is a missed hint, never a failed dispatch")
    void unreadablePayloadDoesNotThrow() {
        // Nothing to name and nothing to throw: `project` is not the aggregate here, so there
        // is no fallback either.
        listener.on(message("pledge", UUID.randomUUID(), "pledge.confirmed", "not json at all"));

        assertThat(invalidator.tags).isEmpty();
    }

    @Test
    @DisplayName("a tag needs both halves of an address, because half a path is a different page")
    void anAddressNeedsBothSlugs() {
        assertThatThrownBy(() -> CacheTags.campaign("ayan", " ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CacheTags.campaign(null, "studio")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a deployment that configures nothing sends nothing and does not fail doing it")
    void anUnconfiguredDeploymentIsQuiet() {
        CacheInvalidationProperties properties = new CacheInvalidationProperties(null, null, null, null, null);
        assertThat(properties.isConfigured()).isFalse();
        // The defaults still have to be usable: a bound record leaves an omitted property at
        // its zero value, and a zero-capacity queue would refuse every batch.
        assertThat(properties.connectTimeout()).isPositive();
        assertThat(properties.readTimeout()).isPositive();
        assertThat(properties.queueCapacity()).isPositive();

        CacheInvalidator invalidator = new CacheInvalidator(
                properties, org.springframework.web.client.RestClient.builder());
        invalidator.invalidate(List.of(CacheTags.project(PROJECT)));
    }

    @Test
    @DisplayName("an endpoint without a secret is not a configuration, it is half of one")
    void bothHalvesAreRequired() {
        assertThat(new CacheInvalidationProperties("https://ideanest.az/x", null, null, null, null).isConfigured())
                .isFalse();
        assertThat(new CacheInvalidationProperties(null, "shhh", null, null, null).isConfigured())
                .isFalse();
        assertThat(new CacheInvalidationProperties(
                                "https://ideanest.az/x", "shhh", Duration.ofSeconds(1), Duration.ofSeconds(1), 8)
                        .isConfigured())
                .isTrue();
    }

    private OutboxMessage message(String aggregateType, UUID aggregateId, String eventType, String payload) {
        return new OutboxMessage(
                UUID.randomUUID(), aggregateType, aggregateId, eventType, payload, Instant.EPOCH, 1);
    }

    /** A stand-in that records rather than sends, so no test needs a socket. */
    private static final class RecordingInvalidator extends CacheInvalidator {

        private final List<String> tags = new ArrayList<>();

        private RecordingInvalidator() {
            super(
                    new CacheInvalidationProperties(null, null, null, null, null),
                    org.springframework.web.client.RestClient.builder());
        }

        @Override
        public void invalidate(java.util.Collection<String> invalidated) {
            tags.addAll(invalidated);
        }
    }
}
