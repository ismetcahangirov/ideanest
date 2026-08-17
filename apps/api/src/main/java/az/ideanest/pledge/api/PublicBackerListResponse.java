package az.ideanest.pledge.api;

import az.ideanest.pledge.application.PublicBackers;
import az.ideanest.pledge.application.RewardTierBackers;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * What a campaign publishes about the people who backed it.
 *
 * <p>Two numbers and a list of numbers, because that is what §4.4 asks for: the backer
 * count in the header, and a backer count beside each tier on the Rewards tab.
 *
 * <p><strong>Nobody is named here, and that is the shape of the answer rather than a
 * filter applied to it.</strong> §4.4 makes backer data public only in aggregate;
 * whether a campaign should publish who backed it is #209, and it is undecided. So
 * this body has no field an identity could occupy — there is nothing for a later
 * change to forget to blank, because there is nothing to blank.
 *
 * <p><strong>Nulls are written out</strong>, so an unbacked campaign answers
 * {@code {"backerCount": 0, "rewardTiers": []}} rather than an object with keys
 * missing: a client should not have to tell "no backers" from "this server does not
 * send that key".
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record PublicBackerListResponse(long backerCount, List<RewardTierCountBody> rewardTiers) {

    /** ASCII unit separator. Cannot occur in a uuid or a number. */
    private static final char FIELD_SEPARATOR = (char) 0x1f;

    /** ASCII record separator, so that a row boundary is not a field boundary. */
    private static final char ROW_SEPARATOR = (char) 0x1e;

    public static PublicBackerListResponse of(PublicBackers.PublicBacking backing) {
        return new PublicBackerListResponse(
                backing.backerCount(),
                backing.rewardTiers().stream()
                        .map(PublicBackerListResponse::tierCount)
                        .toList());
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
     */
    public String etag() {
        StringBuilder canonical = new StringBuilder();
        append(canonical, String.valueOf(backerCount));
        for (RewardTierCountBody tier : rewardTiers) {
            append(canonical, String.valueOf(tier.rewardTierId()), String.valueOf(tier.backerCount()));
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
     *
     * <p>These sum to at most {@link #backerCount}. The difference is §4.5's PL-02,
     * support with no reward, which belongs to no tier.
     */
    public record RewardTierCountBody(UUID rewardTierId, long backerCount) {
    }
}
