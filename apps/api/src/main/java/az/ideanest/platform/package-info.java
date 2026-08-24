/**
 * The platform's own switches and its vital signs — §4.11's AD-12 and AD-16, issues #312
 * and #316.
 *
 * <p><strong>Two modules of §4.11's table in one Java module, which needs an argument.</strong>
 * Feature flags and system health have nothing to do with each other as features. What
 * they share is that neither is about campaigns, money, or people: both are about the
 * running deployment, both are read by whoever operates it, and neither owns enough to be
 * a module on its own — a package with one entity and one service is a directory, not a
 * boundary.
 *
 * <p>The line that would split them is the day either grows a second table. Until then,
 * two modules here would mean two {@code package-info} files making the same claim.
 *
 * <p><strong>Health reads other modules' queues through
 * {@code shared.observability.QueueDepthSource}</strong> rather than their tables. That is
 * what keeps this module from acquiring a dependency on every module that has a queue —
 * and what makes a new queue appear on the screen by adding a bean beside it.
 */
package az.ideanest.platform;
