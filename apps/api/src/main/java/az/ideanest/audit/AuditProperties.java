package az.ideanest.audit;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How much text a caller and a client may put into a row nothing can ever remove.
 *
 * <p>Both bounds exist for the same reason, and it is not tidiness. Every other
 * table in this schema has something that prunes it — a sweep, a cascade, a
 * retention job — and this one has a trigger that refuses DELETE. Text that arrives
 * here stays until the month it was written in is detached, which is not a thing
 * that happens yet. So a client sending a megabyte {@code User-Agent} on every
 * privileged call is not a formatting problem, it is unbounded growth in the one
 * table that cannot be tidied afterwards, bought for the price of one header.
 *
 * <p>Configuration rather than constants because the right number depends on what
 * the deployment is willing to store, and because the answer for a suite proving
 * that truncation happens at all is not the answer for production.
 *
 * @param detailMaxLength what a call site may say beyond the columns. A thousand
 *     characters is several sentences — enough for "changed from these capabilities
 *     to those" and far short of somewhere to put a request body. Applied
 *     <em>after</em> redaction, so a mask cannot be cut in half and leave the
 *     interesting end of the value visible
 * @param userAgentMaxLength what the client called itself. Real user agents run to
 *     a couple of hundred characters; five hundred and twelve keeps every one of
 *     them and cuts anything using the header as a channel
 */
@ConfigurationProperties(prefix = "ideanest.audit")
public record AuditProperties(int detailMaxLength, int userAgentMaxLength) {

    private static final int DEFAULT_DETAIL_MAX_LENGTH = 1000;

    private static final int DEFAULT_USER_AGENT_MAX_LENGTH = 512;

    public AuditProperties {
        // Binding leaves an omitted property at its zero value, and a zero here is a
        // bound that truncates every value to nothing — an audit row with the detail
        // silently removed, which is the failure this whole package exists to avoid.
        detailMaxLength = detailMaxLength == 0 ? DEFAULT_DETAIL_MAX_LENGTH : detailMaxLength;
        userAgentMaxLength = userAgentMaxLength == 0 ? DEFAULT_USER_AGENT_MAX_LENGTH : userAgentMaxLength;

        if (detailMaxLength < 1) {
            throw new IllegalArgumentException("An audit detail that is bounded to nothing is not a detail");
        }
        if (userAgentMaxLength < 1) {
            throw new IllegalArgumentException("A user agent that is bounded to nothing is not a user agent");
        }
    }
}
