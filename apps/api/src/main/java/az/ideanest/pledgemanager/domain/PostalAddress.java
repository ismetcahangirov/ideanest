package az.ideanest.pledgemanager.domain;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Where a parcel goes — §4.8's PM-07.
 *
 * <h2>Structured, and only as structured as the world allows</h2>
 *
 * <p>Seven fields, and the choice of which seven is the whole design. The temptation
 * is a schema per country — a `postcode` that is five digits in Germany and an
 * alphanumeric pair in the United Kingdom, a `state` that is mandatory in one and
 * meaningless in another — and it is the classic mistake: every country that does not
 * fit becomes an address a real person cannot enter. Azerbaijan, where this platform
 * is, has postal codes of the form {@code AZ 1000} and administrative districts that
 * no international form has a box for.
 *
 * <p>So the structure is the part every postal union agrees on — a recipient, one or
 * two lines of street, a locality, a country — and everything that varies is
 * optional. {@code region} and {@code postcode} are <strong>not required</strong>,
 * because there are countries where neither exists, and a required field somebody has
 * nothing to put in is a field they fill with a full stop.
 *
 * <p><strong>Validation is presence and length, never format.</strong> No postcode
 * regular expression, no locality lookup, no "did you mean". Every one of those is a
 * rule that is right for the countries whoever wrote it was thinking about and wrong
 * for somebody's home. What is checked is that a line is not blank, that it is short
 * enough to print on a label, and that the country is a code the platform recognises
 * — which it must be, because it is what shipping was quoted against.
 *
 * <h2>Not an entity</h2>
 *
 * <p>This never becomes columns. {@code ShippingAddress} holds one AES-GCM ciphertext
 * over the whole of it, and this record is what exists on either side of that
 * envelope — see V36 for why the envelope is one field rather than seven, and for
 * what that costs.
 *
 * @param recipient who the parcel is addressed to. Separate from the backer's display
 *     name and from their account: people ship to their office, to a relative, under a
 *     name the platform has never seen
 * @param line2 an apartment, a floor, a company. Optional, because most addresses do
 *     not have one and an empty second line printed on a label is a line somebody
 *     wonders about
 * @param region a state, province, oblast or district. Optional — see above
 * @param postcode optional, and never pattern-checked
 * @param phone what the carrier rings when nobody answers the door. Optional, and
 *     stored inside the envelope with everything else: it is as personal as the
 *     street
 */
public record PostalAddress(
        String recipient,
        String line1,
        String line2,
        String locality,
        String region,
        String postcode,
        String countryCode,
        String phone) {

    /** Long enough for the longest real line, short enough to print. */
    private static final int MAX_LINE = 200;

    private static final int MAX_SHORT = 100;

    /** ISO 3166-1 alpha-2, the same vocabulary {@code shipping_rules.country_code} holds. */
    private static final Pattern COUNTRY = Pattern.compile("^[A-Z]{2}$");

    public PostalAddress {
        recipient = required(recipient, "recipient", MAX_SHORT);
        line1 = required(line1, "line1", MAX_LINE);
        line2 = optional(line2, "line2", MAX_LINE);
        locality = required(locality, "locality", MAX_SHORT);
        region = optional(region, "region", MAX_SHORT);
        postcode = optional(postcode, "postcode", MAX_SHORT);
        phone = optional(phone, "phone", MAX_SHORT);

        countryCode = countryCode == null ? "" : countryCode.trim().toUpperCase(Locale.ROOT);
        if (!COUNTRY.matcher(countryCode).matches()) {
            throw new AddressInvalidException("countryCode", "A destination is a two-letter ISO 3166-1 country code.");
        }
    }

    /**
     * Whether this address is going where the pledge said it was.
     *
     * <p>Asked by {@code ShippingAddressService} rather than enforced here, because
     * the pledge is not this record's to know about. The check exists because
     * {@code pledges.shipping_country} is what shipping was <em>quoted</em> against
     * (§4.5's PL-05): an address in a different country is one the backer was never
     * charged postage for, and accepting it silently makes the creator pay the
     * difference on a parcel they did not price.
     */
    public boolean isGoingTo(String country) {
        return countryCode.equals(country == null ? null : country.trim().toUpperCase(Locale.ROOT));
    }

    private static String required(String value, String field, int max) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            throw new AddressInvalidException(field, "This part of the address is required.");
        }
        if (trimmed.length() > max) {
            throw new AddressInvalidException(field, "This is longer than " + max + " characters.");
        }
        return trimmed;
    }

    /**
     * Absent and blank are the same address.
     *
     * <p>Normalised to null rather than to the empty string, so that one fact has one
     * representation — the same rule V31 states about {@code backer_segments} — and so
     * that a label printer does not have to decide whether {@code ""} is a line.
     */
    private static String optional(String value, String field, int max) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > max) {
            throw new AddressInvalidException(field, "This is longer than " + max + " characters.");
        }
        return trimmed;
    }

    @Override
    public String toString() {
        // Never the contents. This record is a home address, and the one thing that
        // reliably defeats encryption at rest is a log line built by string
        // concatenation during an incident.
        return "PostalAddress[" + Objects.toString(countryCode, "??") + ", redacted]";
    }
}
