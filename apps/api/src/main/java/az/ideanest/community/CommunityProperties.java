package az.ideanest.community;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The community module's settings.
 *
 * <p>Every value has a default that a deployment configuring nothing still gets, for
 * {@code ModerationProperties}' reason: binding leaves an omitted property at its zero
 * value, and a rate limit of zero is an endpoint nobody can call while a page size of
 * zero is a tab that always looks empty. Both are configuration mistakes with no
 * symptom until somebody tries to use the feature.
 *
 * @param comments what a comment endpoint allows
 */
@ConfigurationProperties(prefix = "ideanest.community")
public record CommunityProperties(Comments comments) {

    public CommunityProperties {
        comments = comments == null ? Comments.defaults() : comments;
    }

    public static CommunityProperties defaults() {
        return new CommunityProperties(Comments.defaults());
    }

    /**
     * What a comment endpoint allows.
     *
     * <p><strong>§17.3 names no number for commenting, and that is why these are
     * argued rather than quoted.</strong> The table gives sign-in, registration,
     * two-factor, pledge, search and autocomplete, and for comments it names a
     * different control — "bot traffic: challenge on registration and comment", a
     * CAPTCHA, which does not exist on this platform. Until it does, the limiter is
     * the whole of the defence on a public write surface, so the budget here is set
     * for a person having a conversation rather than for the absence of one.
     *
     * <p><strong>The counter is per replica (#142)</strong>, like every other limit on
     * the platform: with three instances each number below is really three times
     * itself. Closing that needs shared storage the platform does not have.
     *
     * @param perAuthor how many comments one account may post per window. Ten in five
     *     minutes is faster than anybody writes and slower than a script wants
     * @param perClient how many one source address may post per window, counted
     *     separately because the per-account limit alone does not bound somebody with
     *     fifty accounts. Deliberately much looser, for {@code ModerationProperties}'
     *     reason: registration is already limited per address, so acquiring the
     *     accounts is bounded upstream, and a tight per-address number here would
     *     instead refuse the office, university or mobile-carrier NAT a real backer is
     *     sitting behind
     * @param window the period both are measured over
     * @param defaultPageSize how many conversations a request that names no size gets
     * @param maxPageSize the ceiling on that. A bound rather than a preference: this
     *     is an unauthenticated read on the largest table the platform will have
     * @param repliesPerThread how many replies are previewed under each conversation
     *     in the list. The rest are fetched a thread at a time — see
     *     {@code CommentService} — which is what keeps the cost of one page
     *     independent of the size of the argument happening in it
     */
    public record Comments(
            int perAuthor,
            int perClient,
            Duration window,
            int defaultPageSize,
            int maxPageSize,
            int repliesPerThread) {

        private static final int DEFAULT_PER_AUTHOR = 10;

        /** See the record comment for why this is not a small multiple of the above. */
        private static final int DEFAULT_PER_CLIENT = 100;

        private static final Duration DEFAULT_WINDOW = Duration.ofMinutes(5);

        private static final int DEFAULT_PAGE_SIZE = 20;

        private static final int DEFAULT_MAX_PAGE_SIZE = 50;

        private static final int DEFAULT_REPLIES_PER_THREAD = 5;

        static Comments defaults() {
            return new Comments(
                    DEFAULT_PER_AUTHOR,
                    DEFAULT_PER_CLIENT,
                    DEFAULT_WINDOW,
                    DEFAULT_PAGE_SIZE,
                    DEFAULT_MAX_PAGE_SIZE,
                    DEFAULT_REPLIES_PER_THREAD);
        }

        public Comments {
            perAuthor = perAuthor == 0 ? DEFAULT_PER_AUTHOR : perAuthor;
            perClient = perClient == 0 ? DEFAULT_PER_CLIENT : perClient;
            window = window == null ? DEFAULT_WINDOW : window;
            defaultPageSize = defaultPageSize == 0 ? DEFAULT_PAGE_SIZE : defaultPageSize;
            maxPageSize = maxPageSize == 0 ? DEFAULT_MAX_PAGE_SIZE : maxPageSize;
            repliesPerThread = repliesPerThread == 0 ? DEFAULT_REPLIES_PER_THREAD : repliesPerThread;

            if (perAuthor < 1 || perClient < 1) {
                // A limit of zero is the comments feature switched off, which is not a
                // configuration of it. An operator sees this at start-up rather than
                // as every comment being refused.
                throw new IllegalArgumentException("A comment rate limit has to allow at least one attempt");
            }
            if (!window.isPositive()) {
                throw new IllegalArgumentException("A rate limit window has to be a length of time");
            }
            if (defaultPageSize < 1 || maxPageSize < 1 || repliesPerThread < 1) {
                throw new IllegalArgumentException("A page of comments holds at least one comment");
            }
            if (defaultPageSize > maxPageSize) {
                // Otherwise the default is refused by the ceiling and every request
                // that names no size fails, which is a start-up problem wearing a
                // runtime costume.
                throw new IllegalArgumentException("The default page cannot exceed the maximum");
            }
        }
    }
}
