package az.ideanest.realtime.domain;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * One of §12.1's channels, parsed from what a client asked to subscribe to.
 *
 * <p><strong>A closed vocabulary, and the parse is the access control.</strong> There is no
 * authentication on this socket, so the only thing standing between a client and somebody
 * else's data is that no channel here carries any. Both forms name a campaign — which is public
 * — and neither carries anything that is not already on the campaign's public page.
 *
 * <p>That is why {@code user:{id}} is not in this enum rather than being in it and refused: a
 * constant that exists is one a caller writes code against, which is the argument
 * {@code ProjectAudience} makes about its own vocabulary. It arrives when the socket
 * authenticates.
 *
 * <p><strong>The identifier is validated as a UUID and never used to read anything.</strong> A
 * subscription to a campaign that does not exist is accepted and receives nothing, deliberately:
 * checking would mean a database read per socket opened, on a public endpoint, which is a
 * denial-of-service surface bought in exchange for an error message nobody acts on. What a
 * client learns from subscribing to a nonexistent campaign is silence, which is also what it
 * learns from subscribing to a real campaign nobody is backing.
 *
 * @param kind which of §12.1's rows
 * @param projectId the campaign it is about
 */
public record RealtimeChannel(Kind kind, UUID projectId) {

    private static final String PROJECT_PREFIX = "project:";

    private static final String COMMENTS_SUFFIX = ":comments";

    public RealtimeChannel {
        Objects.requireNonNull(kind, "A channel is of some kind");
        Objects.requireNonNull(projectId, "A channel is about some campaign");
    }

    /** §12.1's rows that are built. See the package comment for the four that are not. */
    public enum Kind {

        /** {@code project:{id}} — the pledge counter, aggregated into windows before broadcast. */
        PROJECT,

        /** {@code project:{id}:comments} — that somebody has commented, never what they wrote. */
        COMMENTS
    }

    /**
     * The channel a client named, or null when it named something this server does not serve.
     *
     * <p>Null rather than an exception, because the caller is a socket handshake and the answer
     * to an unknown channel is to close the connection rather than to log a stack trace per
     * malformed query string. It is an unauthenticated endpoint: whatever can be sent will be.
     */
    public static RealtimeChannel parse(String name) {
        if (name == null || name.isBlank() || !name.startsWith(PROJECT_PREFIX)) {
            return null;
        }

        // Lower-cased before anything else so that `Project:` and `PROJECT:` are the same
        // channel rather than two, which matters because the name is a map key: two spellings
        // would be two entries and a broadcast would reach one of them.
        String rest = name.toLowerCase(Locale.ROOT).substring(PROJECT_PREFIX.length());
        Kind kind = Kind.PROJECT;

        if (rest.endsWith(COMMENTS_SUFFIX)) {
            kind = Kind.COMMENTS;
            rest = rest.substring(0, rest.length() - COMMENTS_SUFFIX.length());
        }

        try {
            return new RealtimeChannel(kind, UUID.fromString(rest));
        } catch (IllegalArgumentException notAnIdentifier) {
            // Includes every unknown suffix, because whatever is left of the name after the
            // known ones are stripped has to be an identifier and `project:{id}:dashboard`
            // leaves `{id}:dashboard`, which is not one.
            return null;
        }
    }

    /** The wire name, which is also the key a broadcast is routed by. */
    public String name() {
        return PROJECT_PREFIX + projectId + (kind == Kind.COMMENTS ? COMMENTS_SUFFIX : "");
    }
}
