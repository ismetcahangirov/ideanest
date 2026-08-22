package az.ideanest.pledgemanager.api;

import az.ideanest.pledgemanager.domain.PostalAddress;

/**
 * A postal address, in a request and in a response — §4.8's PM-07.
 *
 * <p>Eight fields, four of them optional, and no country-specific shape. See
 * {@link PostalAddress} for why validation is presence and length rather than format:
 * every postcode pattern is right for the countries whoever wrote it was thinking
 * about and wrong for somebody's home.
 *
 * <p><strong>Nulls are written out.</strong> The address form binds a control to every
 * field, and an absent key cannot be told from a field the backer genuinely left empty
 * — the same reason {@code RewardResponse} says so.
 */
public record PostalAddressBody(
        String recipient,
        String line1,
        String line2,
        String locality,
        String region,
        String postcode,
        String countryCode,
        String phone) {

    public static PostalAddressBody of(PostalAddress address) {
        return new PostalAddressBody(
                address.recipient(),
                address.line1(),
                address.line2(),
                address.locality(),
                address.region(),
                address.postcode(),
                address.countryCode(),
                address.phone());
    }

    /**
     * The validated value.
     *
     * <p>Validation happens in the record's constructor rather than through Jakarta
     * annotations, so that one rule about what an address is holds wherever one is
     * built — including in a test, and including on the day something other than HTTP
     * writes one.
     */
    public PostalAddress toAddress() {
        return new PostalAddress(recipient, line1, line2, locality, region, postcode, countryCode, phone);
    }
}
