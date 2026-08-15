/**
 * Reward tiers, the atomic items they are built from, and stock.
 *
 * <p>Items first, tiers second, which is the order {@code docs/architecture.md} §4.6
 * puts them in. An item is one thing the creator makes; a tier is a selection of items
 * with quantities, a price, a delivery estimate, and a shipping scope. The alternative —
 * a free-text list of contents on each tier — cannot answer "how many mugs do I owe",
 * which is the only question fulfilment asks.
 *
 * <p><strong>The stock limit is a database constraint, not a Java check.</strong>
 * {@code reward_tiers_stock_is_within_the_limit} refuses any row where
 * {@code claimed_quantity + reserved_quantity} exceeds {@code limit_quantity}. A limit
 * enforced only in the application is oversold stock the first time two checkouts race,
 * and the application code that checked would not even be wrong — merely not serialised.
 * Nothing in this module writes either count: reservation is #51, and what is here are
 * the columns and the constraint it will rely on.
 *
 * <p><strong>Authorisation is not decided here.</strong>
 * {@code application.RewardAccess} finds the campaign an item or a tier belongs to and
 * asks {@code project.application.ProjectAccess}, which is the one place in the service
 * where "who may edit this campaign" is settled. This module therefore depends on
 * {@code project.application} and on nothing else of that module — never the reverse, or
 * the slice check in {@code ModuleBoundaryTests} fails on a cycle.
 */
package az.ideanest.reward;
