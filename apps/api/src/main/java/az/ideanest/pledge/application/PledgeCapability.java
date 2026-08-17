package az.ideanest.pledge.application;

/**
 * A step of §9.2 that the platform does not perform yet, named rather than assumed.
 *
 * <p><strong>This is how "not built" is said out loud instead of in a comment
 * somebody deletes.</strong> The same device as {@code DiscoveryCapability}: the
 * missing thing is a constant with the issue that owns it attached, so that a
 * developer who wonders why no card was verified finds the issue rather than the
 * absence of code.
 *
 * <p>Unlike {@code DiscoveryCapability}, nothing here is refused. A discovery query
 * that asks for something unimplemented is answered with a problem detail, because
 * a search that silently ignores the search term has told the user it worked. A
 * confirmation is different: §9.2 is explicit that the card is verified and
 * <em>voided</em>, that no money moves, and that no ledger entry is written — so the
 * state transition and the stock commitment that make up the rest of it are correct
 * and complete on their own. Refusing every confirmation until #55 lands would stop
 * the platform taking pledges at all, for a step that changes no amount and no
 * balance.
 *
 * <p>What must not happen is a client believing more than that. The confirmation
 * response says {@code cardVerified: false} for exactly this reason, and
 * {@code docs/architecture.md} §9.2 carries the same note.
 */
public enum PledgeCapability {

    /**
     * §9.2's phase 1: the verification authorisation, 3-D Secure, storing the card
     * token and the scheme transaction identifier, and voiding the authorisation.
     *
     * <p><strong>#55, which is blocked on #60.</strong> #60 chooses the payment
     * provider, and every part of this step is that provider's API: there is no
     * neutral way to write "authorise and void" against a provider nobody has
     * selected. A stub that returned an approval would be worse than nothing — it
     * would make the confirmation path look finished and would have to be found and
     * removed by whoever implements the real one, having in the meantime told
     * clients that cards were verified when no card was ever seen.
     *
     * <p>{@code pledges.payment_method_id} is accepted and stored today (V17: a
     * nullable column with no foreign key, precisely because {@code payment_methods}
     * is #55's table), so the shape a client sends does not change when this lands.
     */
    CARD_VERIFICATION("#55 (card on file), which is blocked on #60 (the payment provider abstraction)");

    private final String owner;

    PledgeCapability(String owner) {
        this.owner = owner;
    }

    /**
     * What has to exist before this works, named so that a developer is sent to the
     * issue rather than to the source.
     */
    public String owner() {
        return owner;
    }
}
