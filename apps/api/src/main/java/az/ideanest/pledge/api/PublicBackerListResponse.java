package az.ideanest.pledge.api;

import az.ideanest.pledge.application.PublicBacker;
import az.ideanest.pledge.application.PublicBackers;
import az.ideanest.pledge.application.RewardTierBackers;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * What a campaign publishes about the people who backed it.
 *
 * <p>Three things, because §4.4 puts all three on one page: the header's backer count,
 * the Rewards tab's count beside each tier, and a page of the backers themselves.
 *
 * <p><strong>Nulls are written out.</strong> An anonymous entry answers
 * {@code {"isAnonymous": true, "id": null, "name": null, "slug": null, "backedAt":
 * "..."}} rather than an object with three keys missing, so a client does not have to
 * tell "not published" from "this server does not send that key". {@code isAnonymous}
 * is always present and is the discriminator: it is the one field a client should
 * branch on, rather than testing whether a name happens to be there.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record PublicBackerListResponse(
        long backerCount, List<RewardTierCountBody> rewardTiers, List<PublicBackerBody> backers) {

    /** ASCII unit separator. Cannot occur in a uuid, a number, or an ISO 8601 instant. */
    private static final char FIELD_SEPARATOR = (char) 0x1f;

    /** ASCII record separator, so that a row boundary is not a field boundary. */
    private static final char ROW_SEPARATOR = (char) 0x1e;

    /** ASCII group separator, between the tier counts and the backers. */
    private static final char GROUP_SEPARATOR = (char) 0x1d;

    public static PublicBackerListResponse of(PublicBackers.PublicBacking backing) {
        return new PublicBackerListResponse(
                backing.backerCount(),
                backing.rewardTiers().stream()
                        .map(PublicBackerListResponse::tierCount)
                        .toList(),
                backing.backers().stream().map(PublicBackerListResponse::backer).toList());
    }

    /**
     * One backer on the wire.
     *
     * <p><strong>An exhaustive switch over {@link PublicBacker}, not a null check.</strong>
     * This is the single place the two shapes are flattened into the one object JSON
     * can carry, and the sealed hierarchy is what makes it safe: a third variant added
     * to {@code PublicBacker} later will not compile until it is handled here, whereas
     * an {@code if (backer.name() != null)} would have quietly accepted it and
     * published whatever the new variant happened to hold.
     */
    private static PublicBackerBody backer(PublicBacker backer) {
        return switch (backer) {
            case PublicBacker.Named named ->
                new PublicBackerBody(false, named.id(), named.name(), named.slug(), named.backedAt());
            case PublicBacker.Anonymous anonymous -> new PublicBackerBody(true, null, null, null, anonymous.backedAt());
        };
    }

    private static RewardTierCountBody tierCount(RewardTierBackers tier) {
        return new RewardTierCountBody(tier.rewardTierId(), tier.backerCount());
    }

    /**
     * A validator for this exact body, per §10.3.
     *
     * <p>A digest over what is serialised rather than {@code hashCode()}, for the
     * reason {@code PublicRewardListResponse} gives: a tag has to mean the same thing
     * on every instance of the service and after a restart, and nothing guarantees a
     * record's hash does. Computed beside the fields so that adding one and forgetting
     * to cover it is a one-line distance rather than a different file.
     *
     * <p>Every field, {@code isAnonymous} included. A backer who switches the flag
     * without changing anything else has changed this body, and a tag that skipped the
     * flag would hand a cache a 304 for a page still showing the name they just asked
     * to withdraw — which is the one staleness this endpoint must not have.
     */
    public String etag() {
        StringBuilder canonical = new StringBuilder();
        append(canonical, String.valueOf(backerCount));
        for (RewardTierCountBody tier : rewardTiers) {
            append(canonical, String.valueOf(tier.rewardTierId()), String.valueOf(tier.backerCount()));
        }
        canonical.append(GROUP_SEPARATOR);
        for (PublicBackerBody backer : backers) {
            append(
                    canonical,
                    String.valueOf(backer.isAnonymous()),
                    String.valueOf(backer.id()),
                    backer.name(),
                    backer.slug(),
                    String.valueOf(backer.backedAt()));
        }
        return digestOf(canonical.toString());
    }

    private static void append(StringBuilder canonical, String... fields) {
        for (String field : fields) {
            canonical.append(field).append(FIELD_SEPARATOR);
        }
        canonical.append(ROW_SEPARATOR);
    }

    private static String digestOf(String canonical) {
        MessageDigest sha256;
        try {
            sha256 = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // Every JVM ships SHA-256. Reaching here is not a runtime condition.
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
        byte[] digest = sha256.digest(canonical.getBytes(StandardCharsets.UTF_8));
        return "\"" + HexFormat.of().formatHex(digest, 0, 8) + "\"";
    }

    /**
     * §4.4's per-tier backer count.
     *
     * <p>Only tiers with at least one backer appear. A client rendering the Rewards tab
     * reads zero for a tier that is missing here, which is what a missing row means and
     * is one fewer row on the launch day of a campaign with forty tiers and two
     * backers.
     */
    public record RewardTierCountBody(UUID rewardTierId, long backerCount) {
    }

    /**
     * One backer.
     *
     * @param isAnonymous the discriminator. True means the other three fields are null
     *     and always will be — either the backer asked for it (§4.5's PL-12) or their
     *     account has been anonymised (§17.4)
     * @param backedAt when the pledge was confirmed, which is when it became a backing.
     *     Present on an anonymous entry too: a timestamp names nobody
     */
    // Repeated on the nested record because the service's default inclusion is
    // `non_null` (application.yml) and the annotation on the enclosing type does not
    // reach in here. Without it an anonymous entry serialises as two keys, and a
    // client would have to tell "withheld" from "not implemented".
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record PublicBackerBody(boolean isAnonymous, UUID id, String name, String slug, Instant backedAt) {
    }
}
