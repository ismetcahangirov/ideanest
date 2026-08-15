package az.ideanest.reward.domain;

/**
 * How a reward tier reaches the person who backed it.
 *
 * <p>§7.2 lists the five. They are a property of the tier and not of the
 * campaign, because one campaign routinely offers a digital thank-you, a poster
 * shipped domestically, and a boxed set shipped anywhere — and each of those is a
 * different question at checkout: whether to ask for an address at all, and which
 * of the tier's {@code shipping_rules} applies.
 *
 * <p>A text column with a check constraint holds these in the database rather
 * than a native enum, for the reason {@code V6} gives about
 * {@code projects.state}: adding a value is then an ordinary migration.
 */
public enum ShippingType {

    /** Nothing is delivered. A credit in the manual, a thank-you, a name on a wall. */
    NONE,

    /** Delivered as a file or a licence. No address is asked for, and no rule applies. */
    DIGITAL,

    /** Collected in person. No carrier and no rate, but the backer's country still matters for tax. */
    LOCAL_PICKUP,

    /** Shipped inside the campaign's own country only. */
    DOMESTIC,

    /**
     * Shipped anywhere the creator has priced.
     *
     * <p>"Anywhere priced" and not "anywhere": a destination with no rule is a
     * destination the creator has not costed, and quoting one at another
     * country's rate is how a creator ends up paying to ship.
     */
    INTERNATIONAL;

    /** Whether a per-country rate means anything for this tier. */
    public boolean isShipped() {
        return this == DOMESTIC || this == INTERNATIONAL;
    }
}
