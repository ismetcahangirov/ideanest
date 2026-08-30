package az.ideanest.subscription.infrastructure;

import az.ideanest.subscription.domain.SubscriptionPlan;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * V62's catalogue, by the three questions asked of it.
 *
 * <p><strong>The pricing page asks {@link #listed}</strong>, which is the only one that
 * has to be fast and the only one served by an index — {@code subscription_plans_listed_order}
 * is partial for exactly this query.
 *
 * <p><strong>The console asks {@link #catalogue}</strong>, which returns the unlisted ones
 * too. A console showing only what is on sale would hide the plan an operator retired last
 * month, which is the one they are looking for when a subscriber asks about it.
 *
 * <p>Both are unpaged. A catalogue with enough plans to need a cursor is a pricing page
 * nobody can read, and the problem is upstream of this interface.
 */
public interface SubscriptionPlanRepository extends JpaRepository<SubscriptionPlan, UUID> {

    /** What the pricing page offers, in the order it draws them. */
    @Query("SELECT p FROM SubscriptionPlan p WHERE p.listed = true ORDER BY p.sortOrder ASC, p.price ASC")
    List<SubscriptionPlan> listed();

    /** Every plan, listed or not, in the same order. */
    @Query("SELECT p FROM SubscriptionPlan p ORDER BY p.sortOrder ASC, p.price ASC")
    List<SubscriptionPlan> catalogue();

    /**
     * By the code an operator typed.
     *
     * <p>Exists so that a duplicate is refused with a sentence rather than a constraint
     * violation. The unique index is still what makes it true under a race.
     */
    Optional<SubscriptionPlan> findByCode(String code);
}
