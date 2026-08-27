package az.ideanest.user.api;

import java.util.List;

/**
 * A display currency this deployment cannot honour — issue #327.
 *
 * <p>Not a validation failure about the shape: {@code XYZ} is a well-formed currency code
 * and is refused here because no rate for it has been published, or because the feature is
 * off, or because the source has been unreachable long enough that the last rate aged out.
 *
 * <p>It carries the list of what <em>is</em> available, because that is the one thing the
 * client needs and the one thing it cannot work out: the answer changes with the state of a
 * third party, so a client that cached the list at build time would be wrong on exactly the
 * day this refusal happens.
 */
public class UnsupportedDisplayCurrencyException extends RuntimeException {

    private final transient List<String> available;

    public UnsupportedDisplayCurrencyException(String currency, List<String> available) {
        super("This platform cannot show amounts in " + currency + " today");
        this.available = List.copyOf(available);
    }

    /** What a reader may choose right now, including the platform's own currency. */
    public List<String> available() {
        return available;
    }
}
