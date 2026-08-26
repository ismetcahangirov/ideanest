/**
 * Telling the web client that a page it has cached is no longer true — issue #127.
 *
 * <p>The far side caches its public reads for a minute. That is a strategy for load and not
 * for correctness: a backer who pledges watches the total they just moved sit unchanged on the
 * one page where the number is the point, and a creator who publishes an update sends people
 * to a page that does not have it yet. Shortening the window would cost every reader of every
 * campaign a request to make the one campaign that changed correct sooner, and would still not
 * make it correct now.
 *
 * <p>So this package names the campaign that changed. {@link az.ideanest.shared.cache.CacheTags}
 * composes the names — the same vocabulary {@code apps/web/src/lib/cache/tags.ts} recognises —
 * {@link az.ideanest.shared.cache.CacheInvalidationListener} decides which published events
 * deserve one, and {@link az.ideanest.shared.cache.CacheInvalidator} sends them.
 *
 * <p><strong>Every part of it is a hint rather than a guarantee</strong>, and that is what makes
 * it safe to hang off the outbox relay: nothing here throws, nothing here blocks the caller,
 * and a hint that is dropped costs a page that is briefly stale — which is precisely what the
 * platform did before any of this existed. {@code CacheInvalidator} sets out the three
 * consequences in full.
 *
 * <p>A deployment that configures no endpoint does nothing at all, and says so once at
 * start-up.
 */
package az.ideanest.shared.cache;
