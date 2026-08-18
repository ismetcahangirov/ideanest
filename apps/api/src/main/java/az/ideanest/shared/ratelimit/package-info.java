/**
 * §17.3's rate limits: the counter, the key, and what the caller is told.
 *
 * <p>Three pieces, and the split is deliberate. {@link
 * az.ideanest.shared.ratelimit.RateLimiter} counts and decides; {@link
 * az.ideanest.shared.ratelimit.RateLimits} turns a decision into a refusal and
 * into the headers a client backs off on; {@link
 * az.ideanest.shared.ratelimit.ClientAddress} decides what an anonymous caller
 * is counted as. A call site names its own key and its own budget, because what
 * a limit is protecting differs per endpoint — the sign-in limit exists to make
 * guessing expensive, the pledge limit to bound row contention, the search limit
 * to bound query cost — and a single global policy would have to be set for the
 * most sensitive of them and would then be wrong everywhere else.
 *
 * <p><strong>The budgets live in configuration, not here.</strong> Each module
 * owns the numbers for its own endpoints ({@code ideanest.auth.rate-limit},
 * {@code ideanest.pledge.rate-limit}, {@code ideanest.user.rate-limit}), and
 * {@link az.ideanest.shared.ratelimit.AbuseProperties} holds the ones for the
 * public reads that belong to no module's own settings.
 *
 * <p><strong>The ceiling is one process.</strong> {@link
 * az.ideanest.shared.ratelimit.InMemoryRateLimiter} counts in this replica's
 * heap, so every limit here is really the configured number multiplied by the
 * number of replicas. Raising that ceiling needs shared storage, which is #134's;
 * this package is the seam that makes it a substitution rather than a rewrite.
 */
package az.ideanest.shared.ratelimit;
