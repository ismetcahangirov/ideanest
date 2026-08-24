package az.ideanest.admin;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How much of the platform's history one request to the console may take at a time.
 *
 * <p>Three read surfaces arrived with #259 — the audit trail (AD-14), the payment log
 * (AD-05) and the ledger (AD-05) — and each of them reads a table that only ever grows
 * and is never pruned. A page ceiling on those is not a preference about screen density:
 * without one, "give me every audit row the platform has ever written" is one query
 * parameter away, and it is one request in the log rather than the thousand it would
 * take to page through the same thing.
 *
 * <p>Configured rather than hard-coded so that an incident can be worked from a larger
 * page without a deployment. {@code UserProperties.Administration} makes the same trade
 * for AD-04's account list, and the numbers here are deliberately its numbers — a person
 * reading two admin screens should not find that one of them pages differently for a
 * reason nobody can state.
 *
 * @param audit AD-14's trail
 * @param payments AD-05's charge log
 * @param ledger AD-05's postings
 */
@ConfigurationProperties(prefix = "ideanest.admin")
public record AdminConsoleProperties(Paging audit, Paging payments, Paging ledger) {

    public AdminConsoleProperties {
        audit = audit == null ? Paging.defaults() : audit;
        payments = payments == null ? Paging.defaults() : payments;
        ledger = ledger == null ? Paging.defaults() : ledger;
    }

    /**
     * One surface's page sizes.
     *
     * @param defaultPageSize what a request that names no size gets
     * @param maxPageSize the ceiling a larger request is clamped to. Clamped rather than
     *     refused, following the report queue: a client asking for a thousand is asking
     *     for as much as it can have, and a 400 there only teaches it to ask for the
     *     maximum
     */
    public record Paging(int defaultPageSize, int maxPageSize) {

        private static final int DEFAULT_PAGE_SIZE = 25;

        private static final int DEFAULT_MAX_PAGE_SIZE = 100;

        public static Paging defaults() {
            return new Paging(DEFAULT_PAGE_SIZE, DEFAULT_MAX_PAGE_SIZE);
        }

        public Paging {
            defaultPageSize = defaultPageSize == 0 ? DEFAULT_PAGE_SIZE : defaultPageSize;
            maxPageSize = maxPageSize == 0 ? DEFAULT_MAX_PAGE_SIZE : maxPageSize;

            if (defaultPageSize < 1 || maxPageSize < defaultPageSize) {
                throw new IllegalArgumentException("An admin page holds between one row and the maximum");
            }
        }

        /** The size this request will actually get. See {@link #maxPageSize()} on clamping. */
        public int effective(Integer requested) {
            if (requested == null || requested < 1) {
                return defaultPageSize;
            }
            return Math.min(requested, maxPageSize);
        }
    }
}
