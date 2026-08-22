package az.ideanest.payment.domain;

/**
 * A name that does not belong to any provider in §9.3.
 *
 * <p>Raised by {@link ProviderName#of(String)}, which means it is raised from three
 * quite different places: a webhook arriving at {@code /v1/webhooks/psp/{provider}}
 * for a provider nobody has heard of, a deployment configuring a primary that does
 * not exist, and a row read back from {@code transactions.provider} after somebody
 * removed an enum value that data still uses.
 *
 * <p>The first is a 404 and the other two are start-up or read failures, so this
 * carries no HTTP status of its own — the layer that knows which of the three it is
 * decides. What it does carry is the name that was offered, because a message that
 * says only "unknown provider" turns a one-line configuration typo into a search.
 */
public class UnknownProviderException extends RuntimeException {

    private final String offered;

    public UnknownProviderException(String offered) {
        super("No payment provider is named '" + offered + "'");
        this.offered = offered;
    }

    /** What was offered, verbatim, including its capitalisation and any stray space. */
    public String offered() {
        return offered;
    }
}
