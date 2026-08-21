package az.ideanest.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import az.ideanest.realtime.domain.RealtimeChannel;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a client may subscribe to, and everything it may not.
 *
 * <p><strong>This parse is the access control</strong>, which is why it has a suite of its own.
 * The socket needs no credential, so the only thing standing between a caller and data that is
 * not theirs is that no channel here names any. A parse that accepted
 * {@code project:{id}:dashboard} would be a parse that registered a session on a channel §12.1
 * says carries a creator's live metrics — and nothing downstream would notice, because the
 * broadcaster routes by string.
 *
 * <p>A plain unit test: the parse reads a string and returns a record.
 */
class RealtimeChannelTests {

    private static final UUID PROJECT = UUID.fromString("6f1c0f28-5f39-4f3c-9a0f-4a2f0f1c0f28");

    @Test
    @DisplayName("a campaign's counter channel is accepted")
    void theCounterChannelIsAccepted() {
        RealtimeChannel channel = RealtimeChannel.parse("project:" + PROJECT);

        assertThat(channel).isNotNull();
        assertThat(channel.kind()).isEqualTo(RealtimeChannel.Kind.PROJECT);
        assertThat(channel.projectId()).isEqualTo(PROJECT);
    }

    @Test
    @DisplayName("a campaign's comments channel is accepted")
    void theCommentsChannelIsAccepted() {
        RealtimeChannel channel = RealtimeChannel.parse("project:" + PROJECT + ":comments");

        assertThat(channel).isNotNull();
        assertThat(channel.kind()).isEqualTo(RealtimeChannel.Kind.COMMENTS);
        assertThat(channel.projectId()).isEqualTo(PROJECT);
    }

    /**
     * The name a broadcast is routed by is the name a client asked for.
     *
     * <p>Asserted in both directions, because the two live in different places — a client builds
     * the query string and {@code RealtimeAggregator} builds the map key — and a round trip that
     * did not close would be a subscriber on a channel nothing ever broadcasts to.
     */
    @Test
    @DisplayName("a parsed channel names itself the way it was named")
    void theNameRoundTrips() {
        for (String name : new String[] {"project:" + PROJECT, "project:" + PROJECT + ":comments"}) {
            assertThat(RealtimeChannel.parse(name).name()).isEqualTo(name);
        }
    }

    /** Case is folded, so two spellings cannot become two map entries and half a broadcast. */
    @Test
    @DisplayName("the channel name is case-insensitive")
    void theNameIsFolded() {
        RealtimeChannel upper = RealtimeChannel.parse("project:" + PROJECT.toString().toUpperCase(java.util.Locale.ROOT));

        assertThat(upper).isNotNull();
        assertThat(upper.name()).isEqualTo("project:" + PROJECT);
    }

    /**
     * §12.1's four unbuilt channels, refused by not existing.
     *
     * <p>{@code user:{id}} is the one that matters: it carries a person's own notifications, and
     * an unauthenticated socket that accepted it would be an unauthenticated socket delivering
     * somebody's inbox to whoever guessed their identifier.
     */
    @Test
    @DisplayName("a channel this server does not serve is refused")
    void anUnservedChannelIsRefused() {
        assertThat(RealtimeChannel.parse("user:" + PROJECT)).isNull();
        assertThat(RealtimeChannel.parse("project:" + PROJECT + ":dashboard")).isNull();
        assertThat(RealtimeChannel.parse("project:" + PROJECT + ":updates")).isNull();
    }

    @Test
    @DisplayName("a malformed channel is refused rather than raising")
    void aMalformedChannelIsRefused() {
        assertThat(RealtimeChannel.parse(null)).isNull();
        assertThat(RealtimeChannel.parse("")).isNull();
        assertThat(RealtimeChannel.parse("   ")).isNull();
        assertThat(RealtimeChannel.parse("project:")).isNull();
        assertThat(RealtimeChannel.parse("project:not-a-uuid")).isNull();
        assertThat(RealtimeChannel.parse("nonsense")).isNull();
        assertThat(RealtimeChannel.parse("../../etc/passwd")).isNull();
    }
}
