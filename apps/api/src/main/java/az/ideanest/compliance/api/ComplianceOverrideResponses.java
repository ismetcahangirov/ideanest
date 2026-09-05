package az.ideanest.compliance.api;

import az.ideanest.compliance.domain.ComplianceOverride;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What the override endpoints answer with — issue #436.
 *
 * <p>Assembled here rather than by serialising the entity, following every other module's API
 * layer: an entity on the wire is a schema change every time a column moves.
 *
 * <p><strong>{@code live} is computed on the server and sent.</strong> The client has
 * {@code grantedAt}, {@code expiresAt} and {@code revokedAt} and could work it out, and the
 * second client to work it out would get the half-open end of the window wrong. One
 * comparison, in the place that owns the rule.
 */
public final class ComplianceOverrideResponses {

    private ComplianceOverrideResponses() {
    }

    /**
     * One override, as the account screen draws it.
     *
     * @param live whether it is doing anything now. See the class comment on why this is not
     *     left to the reader
     * @param grantedBy the account that authorised the exception. Named rather than
     *     anonymised because that is the entire value of the record — V66 makes the column
     *     RESTRICT for the same reason
     */
    public record Override(
            UUID id,
            UUID subjectUserId,
            String requirement,
            String reason,
            String note,
            UUID grantedBy,
            Instant grantedAt,
            Instant expiresAt,
            Instant revokedAt,
            UUID revokedBy,
            boolean live) {

        public static Override of(ComplianceOverride override, Instant at) {
            return new Override(
                    override.id(),
                    override.subjectUserId(),
                    override.requirement().name(),
                    override.reason().name(),
                    override.note(),
                    override.grantedBy(),
                    override.grantedAt(),
                    override.expiresAt(),
                    override.revokedAt(),
                    override.revokedBy(),
                    override.isLiveAt(at));
        }
    }

    /**
     * Every override an account has ever been granted, newest first.
     *
     * @param liveCount how many are in force now. On the envelope rather than counted by the
     *     client, because it is what the account screen leads with and a screen that says
     *     "three exceptions" when two of them expired last year is a screen that stops being
     *     read
     */
    public record History(UUID subjectUserId, List<Override> overrides, int liveCount) {

        public static History of(UUID subjectUserId, List<ComplianceOverride> overrides, Instant at) {
            List<Override> rendered =
                    overrides.stream().map(override -> Override.of(override, at)).toList();
            int live = (int) rendered.stream().filter(Override::live).count();
            return new History(subjectUserId, rendered, live);
        }
    }
}
