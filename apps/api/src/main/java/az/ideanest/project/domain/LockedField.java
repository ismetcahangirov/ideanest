package az.ideanest.project.domain;

/**
 * A field §5.3 stops accepting changes to once a campaign has launched.
 *
 * <p>An enum rather than a set of string constants, so that
 * {@link ProjectEditLocks} is a table over a closed vocabulary and a typo cannot
 * quietly add a sixth rule nobody wrote down. The strings still exist, because a
 * refusal has to name the input the creator can see: {@link #wireName()} is the
 * key in the {@code PATCH} body and in {@code lockedFields}, not the column and
 * not the Java field.
 *
 * <p><strong>Two resources, one table.</strong> The goal and the deadline are
 * edited through {@code PATCH /v1/projects/{id}} and a tier's price through
 * {@code PATCH /v1/rewards/{id}}, so the names below belong to two different
 * bodies. They are enumerated together anyway: §5.3 freezes them for one reason
 * and at one moment, and splitting the rule across the two modules that enforce it
 * is how the campaign's goal and its reward prices come to disagree about when a
 * campaign counts as launched. {@link #resource()} is what keeps a response from
 * offering a client a field name its own body does not have.
 */
public enum LockedField {

    /** §5.3: immutable after launch. What §5.1 compares the pledged total against. */
    GOAL("goal", Resource.PROJECT),

    /**
     * §5.3 freezes the <em>deadline</em>, which this schema does not store as an
     * editable field: {@code deadline} is computed once, at the edge into
     * {@link ProjectState#LIVE}, from the launch instant and the duration. Freezing
     * the deadline therefore means freezing the two inputs a creator can reach, and
     * this is the one that survives launch as an editable column.
     */
    DURATION_DAYS("durationDays", Resource.PROJECT),

    /**
     * The other half of the deadline. A scheduled launch is what {@code launched_at}
     * would have been, and a campaign that is already live has spent it — editing it
     * afterwards would either mean nothing or, read literally, move the moment the
     * deadline was measured from.
     */
    SCHEDULED_LAUNCH_AT("scheduledLaunchAt", Resource.PROJECT),

    /** §5.3: immutable after launch. The number a backer's card is charged. */
    REWARD_PRICE("price", Resource.REWARD),

    /**
     * The one <em>directional</em> lock. §5.3 permits raising a reward's quantity
     * after launch and forbids lowering it, so what is frozen is the direction
     * rather than the field: the floor a live tier can be lowered to is the limit it
     * already advertises.
     *
     * <p>Named for the direction and not for the field, because the field is still
     * writable and a rule called {@code REWARD_LIMIT_QUANTITY} would read as though
     * it were not. It is also why {@link Resource#REWARD} entries never reach a
     * client's {@code lockedFields}: "locked" there means "will be refused", and
     * that is not true of a quantity being raised.
     */
    REWARD_QUANTITY_DECREASE("limitQuantity", Resource.REWARD);

    /** Which request body the name belongs to. */
    public enum Resource {

        /** {@code PATCH /v1/projects/{id}}. */
        PROJECT,

        /** {@code PATCH /v1/rewards/{id}}. */
        REWARD
    }

    private final String wireName;
    private final Resource resource;

    LockedField(String wireName, Resource resource) {
        this.wireName = wireName;
        this.resource = resource;
    }

    /**
     * The JSON key, exactly as a client sends it — {@code durationDays}, not
     * {@code duration_days}. A client cannot disable an input it has no name for,
     * and it must not have to translate one.
     */
    public String wireName() {
        return wireName;
    }

    public Resource resource() {
        return resource;
    }
}
