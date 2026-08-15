package az.ideanest.reward;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import az.ideanest.shared.Identifiers;
import az.ideanest.support.AbstractIntegrationTest;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * What the database refuses about items, reward tiers, and shipping.
 *
 * <p>The test that carries the design is
 * {@link #theStockLimitIsEnforcedByTheDatabase()}. Every other rule here could be
 * enforced in Java with roughly the same effect; that one cannot, because its failure
 * mode is two concurrent checkouts that both read "one place left" and both find it
 * available. §7.2 states the constraint and §5.3 depends on it, and #51 will build
 * reservation on top of it — so it is checked here, against a real PostgreSQL, rather
 * than trusted.
 *
 * <p>Every rule is also enforced in Java. That is not duplication, for the reason
 * {@code ProjectSchemaTests} gives: an application check is enforced by whichever code
 * path remembered to call it, and a constraint is enforced against a migration, a
 * support query, a bulk import, and a bug.
 *
 * <p>Deliberately not {@code @Transactional}: a statement that violates a constraint
 * aborts the surrounding transaction, so each of these needs its own. A
 * {@link JdbcTemplate} against an auto-committing connection gives exactly that — which
 * also means a {@code DEFERRABLE} constraint is checked at the end of each statement's
 * own implicit transaction, so {@link #anItemInsideARewardCannotBeDeleted()} still sees
 * its refusal here.
 */
class RewardSchemaTests extends AbstractIntegrationTest {

    /**
     * Distinguishes the accounts these tests create.
     *
     * <p>A counter rather than a slice of the identifier: UUID version 7 begins with a
     * millisecond timestamp, so two accounts created in the same millisecond share their
     * leading digits and the test would fail on the unique email index for a reason that
     * has nothing to do with what it is checking.
     */
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;

    private JdbcTemplate jdbc() {
        if (jdbc == null) {
            jdbc = new JdbcTemplate(dataSource);
        }
        return jdbc;
    }

    @AfterEach
    void clearRewards() {
        // In dependency order rather than by cascade, because these tests deliberately
        // leave rows in states a cascade is being asked about elsewhere. The users are
        // left for the identity tests' own cleanup; projects reference them and
        // deliberately do not cascade.
        jdbc().update("DELETE FROM shipping_rules");
        jdbc().update("DELETE FROM reward_tier_items");
        jdbc().update("DELETE FROM reward_tiers");
        jdbc().update("DELETE FROM items");
        jdbc().update("DELETE FROM project_state_transitions");
        jdbc().update("DELETE FROM projects");
    }

    // -----------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------

    private UUID insertUser() {
        UUID id = Identifiers.newIdentifier();
        String marker = "reward-schema-" + SEQUENCE.incrementAndGet();
        jdbc().update(
                        "INSERT INTO users (id, email, name, slug) VALUES (?, ?::citext, ?, ?)",
                        id,
                        marker + "@example.com",
                        "Test Creator",
                        marker);
        return id;
    }

    private UUID insertProject() {
        UUID id = Identifiers.newIdentifier();
        jdbc().update(
                        "INSERT INTO projects (id, creator_id, slug, title) VALUES (?, ?, ?, ?)",
                        id,
                        insertUser(),
                        "a-campaign-" + SEQUENCE.incrementAndGet(),
                        "A campaign");
        return id;
    }

    private UUID insertItem(UUID projectId, String name) {
        UUID id = Identifiers.newIdentifier();
        jdbc().update("INSERT INTO items (id, project_id, name) VALUES (?, ?, ?)", id, projectId, name);
        return id;
    }

    private UUID insertTier(UUID projectId) {
        UUID id = Identifiers.newIdentifier();
        jdbc().update(
                        "INSERT INTO reward_tiers (id, project_id, title, amount) VALUES (?, ?, ?, ?)",
                        id,
                        projectId,
                        "A reward",
                        new BigDecimal("19.99"));
        return id;
    }

    private void compose(UUID projectId, UUID tierId, UUID itemId, int quantity) {
        jdbc().update(
                        "INSERT INTO reward_tier_items (reward_tier_id, item_id, project_id, quantity)"
                                + " VALUES (?, ?, ?, ?)",
                        tierId,
                        itemId,
                        projectId,
                        quantity);
    }

    private Instant timestamp(String column, UUID tierId) {
        return jdbc().queryForObject(
                        "SELECT " + column + " FROM reward_tiers WHERE id = ?", Instant.class, tierId);
    }

    private int count(String table, String column, UUID value) {
        Integer count = jdbc().queryForObject(
                        "SELECT count(*) FROM " + table + " WHERE " + column + " = ?", Integer.class, value);
        return count == null ? 0 : count;
    }

    // -----------------------------------------------------------------------
    // The constraint this migration exists for
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("§7.2: claimed + reserved never exceeds the limit, and the database says so")
    void theStockLimitIsEnforcedByTheDatabase() {
        UUID tierId = insertTier(insertProject());
        jdbc().update("UPDATE reward_tiers SET limit_quantity = 10 WHERE id = ?", tierId);

        // Exactly full is allowed: the last place is a place.
        assertThatCode(() -> jdbc().update(
                        "UPDATE reward_tiers SET claimed_quantity = 6, reserved_quantity = 4 WHERE id = ?", tierId))
                .doesNotThrowAnyException();

        // One more of either is an oversold reward, which is a promise to a backer
        // that the creator cannot keep. This is the failure two concurrent checkouts
        // produce, and no application check can refuse it on its own.
        assertThatThrownBy(() -> jdbc().update(
                        "UPDATE reward_tiers SET reserved_quantity = 5 WHERE id = ?", tierId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc().update(
                        "UPDATE reward_tiers SET claimed_quantity = 7 WHERE id = ?", tierId))
                .isInstanceOf(DataIntegrityViolationException.class);

        // And lowering the limit under what is taken is the same violation from the
        // other direction. §5.3 permits lowering a quantity only above what is
        // claimed, and this is why that rule cannot be quietly skipped.
        assertThatThrownBy(() -> jdbc().update("UPDATE reward_tiers SET limit_quantity = 9 WHERE id = ?", tierId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("an unlimited tier has no ceiling to exceed")
    void anUnlimitedTierAcceptsAnyStock() {
        UUID tierId = insertTier(insertProject());

        // Null limit is unlimited, which is the common case; the constraint has to
        // let it through rather than treat null as zero.
        assertThatCode(() -> jdbc().update(
                        "UPDATE reward_tiers SET claimed_quantity = 5000, reserved_quantity = 12 WHERE id = ?",
                        tierId))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("stock counts and limits cannot be negative")
    void stockIsNotNegative() {
        UUID tierId = insertTier(insertProject());

        assertThatThrownBy(() -> jdbc().update(
                        "UPDATE reward_tiers SET claimed_quantity = -1 WHERE id = ?", tierId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc().update(
                        "UPDATE reward_tiers SET reserved_quantity = -1 WHERE id = ?", tierId))
                .isInstanceOf(DataIntegrityViolationException.class);
        // A limit of zero is not an unlimited tier, it is a tier nobody can select.
        assertThatThrownBy(() -> jdbc().update("UPDATE reward_tiers SET limit_quantity = 0 WHERE id = ?", tierId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // -----------------------------------------------------------------------
    // reward_tiers
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("a price is exact and more than nothing")
    void thePriceIsExactAndPositive() {
        UUID tierId = insertTier(insertProject());

        BigDecimal amount =
                jdbc().queryForObject("SELECT amount FROM reward_tiers WHERE id = ?", BigDecimal.class, tierId);
        // numeric(14,2), not a float. This is the number a card is charged, and the
        // scale comes back as it was stored.
        assertThat(amount).isEqualTo(new BigDecimal("19.99"));

        // §5.3 puts the floor at the smallest chargeable amount. Zero and below are
        // not prices at all.
        assertThatThrownBy(() -> jdbc().update("UPDATE reward_tiers SET amount = 0 WHERE id = ?", tierId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc().update("UPDATE reward_tiers SET amount = -1 WHERE id = ?", tierId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("a shipping scope is one of the five of §7.2")
    void theShippingTypeIsFromTheKnownSet() {
        UUID tierId = insertTier(insertProject());

        for (String known : new String[] {"NONE", "DIGITAL", "LOCAL_PICKUP", "DOMESTIC", "INTERNATIONAL"}) {
            assertThatCode(() -> jdbc().update(
                            "UPDATE reward_tiers SET shipping_type = ? WHERE id = ?", known, tierId))
                    .doesNotThrowAnyException();
        }

        // A scope nobody implemented is a tier checkout cannot decide about: whether
        // to ask for an address at all is read from this column.
        assertThatThrownBy(() -> jdbc().update(
                        "UPDATE reward_tiers SET shipping_type = 'FREE' WHERE id = ?", tierId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("a secret tier has a token, a public one has none, and no two share one")
    void secrecyAndTokenMoveTogether() {
        UUID projectId = insertProject();
        UUID tierId = insertTier(projectId);
        String token = "abcdefghijklmnopqrstuvwxyz012345";

        // Secret without a token is a tier nobody can reach: it is absent from the
        // public list, and the link is the only other way in.
        assertThatThrownBy(() -> jdbc().update("UPDATE reward_tiers SET is_secret = true WHERE id = ?", tierId))
                .isInstanceOf(DataIntegrityViolationException.class);
        // And a token on a public tier is a link the creator believes is private.
        assertThatThrownBy(() -> jdbc().update(
                        "UPDATE reward_tiers SET secret_token = ? WHERE id = ?", token, tierId))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatCode(() -> jdbc().update(
                        "UPDATE reward_tiers SET is_secret = true, secret_token = ? WHERE id = ?", token, tierId))
                .doesNotThrowAnyException();

        // Too short to be unguessable, and a shape that could not survive a URL.
        UUID other = insertTier(projectId);
        assertThatThrownBy(() -> jdbc().update(
                        "UPDATE reward_tiers SET is_secret = true, secret_token = 'short' WHERE id = ?", other))
                .isInstanceOf(DataIntegrityViolationException.class);

        // One link, one tier. Two rows would make the link resolve to whichever the
        // planner found.
        assertThatThrownBy(() -> jdbc().update(
                        "UPDATE reward_tiers SET is_secret = true, secret_token = ? WHERE id = ?", token, other))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("a secret tier cannot also be featured")
    void secretTiersAreNotFeatured() {
        UUID tierId = insertTier(insertProject());

        // Featured means shown first; secret means not shown at all. A row claiming
        // both leaves the campaign page to guess.
        assertThatThrownBy(() -> jdbc().update(
                        "UPDATE reward_tiers SET is_secret = true, secret_token = 'abcdefghijklmnopqrstuvwxyz012345',"
                                + " is_featured = true WHERE id = ?",
                        tierId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("an early bird closes at a time or runs out of places")
    void earlyBirdsEnd() {
        UUID tierId = insertTier(insertProject());

        // Otherwise it is an ordinary tier with a label that hurries a backer for no
        // reason.
        assertThatThrownBy(() -> jdbc().update("UPDATE reward_tiers SET is_early_bird = true WHERE id = ?", tierId))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatCode(() -> jdbc().update(
                        "UPDATE reward_tiers SET is_early_bird = true, limit_quantity = 100 WHERE id = ?", tierId))
                .doesNotThrowAnyException();

        UUID timed = insertTier(insertProject());
        assertThatCode(() -> jdbc().update(
                        "UPDATE reward_tiers SET is_early_bird = true, available_until = now() + interval '7 days'"
                                + " WHERE id = ?",
                        timed))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("an availability window closes after it opens")
    void theAvailabilityWindowIsOrdered() {
        UUID tierId = insertTier(insertProject());

        assertThatThrownBy(() -> jdbc().update(
                        "UPDATE reward_tiers SET available_from = now(), available_until = now() - interval '1 day'"
                                + " WHERE id = ?",
                        tierId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("updated_at moves on update and created_at does not")
    void updatedAtIsMaintainedByTheDatabase() {
        UUID tierId = insertTier(insertProject());
        Instant createdAt = timestamp("created_at", tierId);
        Instant before = timestamp("updated_at", tierId);

        jdbc().update("UPDATE reward_tiers SET description = 'A sentence' WHERE id = ?", tierId);

        // The application never sets updated_at, so it cannot forget to -- and the
        // editor's "last saved" reading is the database's clock, not a client's.
        assertThat(timestamp("updated_at", tierId)).isAfterOrEqualTo(before);
        assertThat(timestamp("created_at", tierId)).isEqualTo(createdAt);
    }

    // -----------------------------------------------------------------------
    // items
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("a digital item has no shipping weight, and a weight is more than zero")
    void digitalItemsHaveNoWeight() {
        UUID itemId = insertItem(insertProject(), "A hardcover book");

        assertThatCode(() -> jdbc().update("UPDATE items SET weight_grams = 800 WHERE id = ?", itemId))
                .doesNotThrowAnyException();

        // A weight against a file would be summed into a shipping quote for something
        // that is not shipped -- silently, and only visible in the total.
        assertThatThrownBy(() -> jdbc().update("UPDATE items SET is_digital = true WHERE id = ?", itemId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc().update("UPDATE items SET weight_grams = 0 WHERE id = ?", itemId))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatCode(() -> jdbc().update(
                        "UPDATE items SET is_digital = true, weight_grams = NULL WHERE id = ?", itemId))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a stock code is unique within a campaign and not globally")
    void skuIsUniquePerCampaign() {
        UUID projectId = insertProject();
        UUID first = insertItem(projectId, "A mug");
        UUID second = insertItem(projectId, "A second mug");
        UUID elsewhere = insertItem(insertProject(), "Somebody else's mug");

        jdbc().update("UPDATE items SET sku = 'MUG-01' WHERE id = ?", first);

        // The whole purpose of the code is to identify one item in a warehouse.
        assertThatThrownBy(() -> jdbc().update("UPDATE items SET sku = 'MUG-01' WHERE id = ?", second))
                .isInstanceOf(DataIntegrityViolationException.class);

        // Two creators may both call something MUG-01; neither warehouse is confused.
        assertThatCode(() -> jdbc().update("UPDATE items SET sku = 'MUG-01' WHERE id = ?", elsewhere))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a name outside 1-120 characters is refused")
    void itemNameLengthIsEnforced() {
        UUID itemId = insertItem(insertProject(), "A mug");

        assertThatThrownBy(() -> jdbc().update("UPDATE items SET name = '   ' WHERE id = ?", itemId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc().update("UPDATE items SET name = ? WHERE id = ?", "n".repeat(121), itemId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // -----------------------------------------------------------------------
    // reward_tier_items
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("a tier cannot be composed from another campaign's item")
    void compositionCannotCrossCampaigns() {
        UUID projectId = insertProject();
        UUID tierId = insertTier(projectId);
        UUID ourItem = insertItem(projectId, "Our mug");

        UUID otherProject = insertProject();
        UUID theirItem = insertItem(otherProject, "Their mug");

        assertThatCode(() -> compose(projectId, tierId, ourItem, 2)).doesNotThrowAnyException();

        // A creator's product inside a stranger's reward. The composite foreign keys
        // are what refuse it; no single-column reference could.
        assertThatThrownBy(() -> compose(projectId, tierId, theirItem, 1))
                .isInstanceOf(DataIntegrityViolationException.class);
        // Nor by claiming the other campaign, which the tier does not belong to.
        assertThatThrownBy(() -> compose(otherProject, tierId, theirItem, 1))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("a tier contains at least one of an item, and lists it once")
    void compositionQuantitiesArePositiveAndUnique() {
        UUID projectId = insertProject();
        UUID tierId = insertTier(projectId);
        UUID itemId = insertItem(projectId, "A mug");

        assertThatThrownBy(() -> compose(projectId, tierId, itemId, 0))
                .isInstanceOf(DataIntegrityViolationException.class);

        compose(projectId, tierId, itemId, 1);
        // Two rows for one item would be a composition that has to be summed to be
        // read, and one of the two would eventually be edited alone.
        assertThatThrownBy(() -> compose(projectId, tierId, itemId, 1))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("an item inside a reward cannot be deleted")
    void anItemInsideARewardCannotBeDeleted() {
        UUID projectId = insertProject();
        UUID tierId = insertTier(projectId);
        UUID itemId = insertItem(projectId, "A mug");
        compose(projectId, tierId, itemId, 1);

        // Deleting it would change what a backer was promised. The endpoint refuses
        // with 409 ITEM_IN_USE before reaching here; this is what holds when the
        // caller is a support query rather than the endpoint.
        assertThatThrownBy(() -> jdbc().update("DELETE FROM items WHERE id = ?", itemId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("deleting a campaign takes its items, tiers, composition, and rates with it")
    void everythingCascadesFromTheCampaign() {
        UUID projectId = insertProject();
        UUID tierId = insertTier(projectId);
        UUID itemId = insertItem(projectId, "A mug");
        compose(projectId, tierId, itemId, 1);
        jdbc().update(
                        "INSERT INTO shipping_rules (reward_tier_id, country_code, amount) VALUES (?, 'AZ', 5.00)",
                        tierId);

        // The reason the item reference is DEFERRABLE and not RESTRICT. This one
        // statement removes the item and the row that refers to it, and a check
        // performed the moment the item went would refuse it on the strength of a row
        // the same statement was about to delete.
        assertThatCode(() -> jdbc().update("DELETE FROM projects WHERE id = ?", projectId))
                .doesNotThrowAnyException();

        assertThat(count("items", "project_id", projectId)).isZero();
        assertThat(count("reward_tiers", "project_id", projectId)).isZero();
        assertThat(count("reward_tier_items", "project_id", projectId)).isZero();
        assertThat(count("shipping_rules", "reward_tier_id", tierId)).isZero();
    }

    @Test
    @DisplayName("deleting a tier takes its composition and its rates, and leaves the items alone")
    void tiersCascadeToTheirOwnRows() {
        UUID projectId = insertProject();
        UUID tierId = insertTier(projectId);
        UUID itemId = insertItem(projectId, "A mug");
        compose(projectId, tierId, itemId, 1);
        jdbc().update(
                        "INSERT INTO shipping_rules (reward_tier_id, country_code, amount) VALUES (?, 'AZ', 5.00)",
                        tierId);

        jdbc().update("DELETE FROM reward_tiers WHERE id = ?", tierId);

        assertThat(count("reward_tier_items", "reward_tier_id", tierId)).isZero();
        assertThat(count("shipping_rules", "reward_tier_id", tierId)).isZero();
        // The item survives: it is a thing the creator makes, not a thing this tier
        // owned.
        assertThat(count("items", "id", itemId)).isEqualTo(1);
    }

    // -----------------------------------------------------------------------
    // shipping_rules
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("one rate per destination, a two-letter code, and nothing negative")
    void shippingRulesAreWellFormed() {
        UUID tierId = insertTier(insertProject());

        assertThatCode(() -> jdbc().update(
                        "INSERT INTO shipping_rules (reward_tier_id, country_code, amount, additional_item_amount)"
                                + " VALUES (?, 'AZ', 5.00, 1.00)",
                        tierId))
                .doesNotThrowAnyException();

        // Two rates for one destination would be two answers to one question at
        // checkout.
        assertThatThrownBy(() -> jdbc().update(
                        "INSERT INTO shipping_rules (reward_tier_id, country_code, amount) VALUES (?, 'AZ', 7.00)",
                        tierId))
                .isInstanceOf(DataIntegrityViolationException.class);

        // Lowercase and three letters are both codes nothing in the platform matches:
        // an address form, a carrier, and a tax rule all speak alpha-2.
        assertThatThrownBy(() -> jdbc().update(
                        "INSERT INTO shipping_rules (reward_tier_id, country_code, amount) VALUES (?, 'az', 7.00)",
                        tierId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc().update(
                        "INSERT INTO shipping_rules (reward_tier_id, country_code, amount) VALUES (?, 'AZE', 7.00)",
                        tierId))
                .isInstanceOf(DataIntegrityViolationException.class);

        // Free shipping is zero. A negative rate would be a discount applied through
        // the shipping line, which makes the pledge total unexplainable to a backer.
        assertThatThrownBy(() -> jdbc().update(
                        "INSERT INTO shipping_rules (reward_tier_id, country_code, amount) VALUES (?, 'TR', -1.00)",
                        tierId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatCode(() -> jdbc().update(
                        "INSERT INTO shipping_rules (reward_tier_id, country_code, amount) VALUES (?, 'TR', 0)",
                        tierId))
                .doesNotThrowAnyException();
    }
}
