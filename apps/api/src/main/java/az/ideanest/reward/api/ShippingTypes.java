package az.ideanest.reward.api;

import az.ideanest.reward.application.RewardFieldRejectedException;
import az.ideanest.reward.domain.ShippingType;
import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Reads a shipping scope out of a request body.
 *
 * <p>The scope arrives as a string and is parsed here rather than bound straight to
 * the enum. Jackson's own failure for an unknown constant is an unreadable-message
 * error that names a Java type and lists nothing the client can choose from; this says
 * which values exist and which field to look at, which is the difference between a
 * creator fixing a select and a creator writing to support.
 *
 * <p>Shared by the create and the patch bodies, so the two cannot come to disagree
 * about what an unknown value means.
 */
final class ShippingTypes {

    private ShippingTypes() {
    }

    /** @return null for a null input, which a patch reads as "no shipping" */
    static ShippingType parse(String value) {
        if (value == null) {
            return null;
        }
        try {
            return ShippingType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new RewardFieldRejectedException(
                    "shippingType",
                    "A shipping scope is one of "
                            + Arrays.stream(ShippingType.values())
                                    .map(ShippingType::name)
                                    .collect(Collectors.joining(", "))
                            + ".");
        }
    }
}
