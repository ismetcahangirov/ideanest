package az.ideanest.pledge.api;

import az.ideanest.pledge.application.BackerFilter;
import az.ideanest.pledge.application.BackerSegment;
import az.ideanest.pledge.domain.PledgeState;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * A saved segment on the wire.
 *
 * <p>The filter comes back in the <strong>same shape it was sent in</strong>, as a
 * {@link BackerFilterBody}, so a client can load a segment straight into the filter
 * controls without a translation layer. An absent array means "this axis does not narrow
 * anything", which is what it meant on the way in and what {@code NULL} means in the
 * column.
 *
 * <p><strong>No membership count.</strong> A segment stores a question and the answer
 * moves; putting a stale count on the row a creator reads is how a bulk message gets sent
 * to a number nobody checked. The count is {@code matched} on the report, computed when
 * the filter is actually run.
 *
 * @param createdBy who saved it. Not who may use it — every holder of VIEW_FINANCES on the
 *     campaign may
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BackerSegmentResponse(
        UUID id, String name, BackerFilterBody filter, UUID createdBy, Instant createdAt, Instant updatedAt) {

    public static BackerSegmentResponse of(BackerSegment segment) {
        return new BackerSegmentResponse(
                segment.id(),
                segment.name(),
                bodyOf(segment.filter()),
                segment.createdBy(),
                segment.createdAt(),
                segment.updatedAt());
    }

    /**
     * The stored filter as the body shape.
     *
     * <p>Sorted, so that two reads of an unchanged segment produce the same JSON — the
     * sets behind it have no order of their own, and a body that reshuffled would defeat
     * any conditional request a client made against it.
     */
    private static BackerFilterBody bodyOf(BackerFilter filter) {
        return new BackerFilterBody(
                filter.states().isEmpty()
                        ? null
                        : filter.states().stream()
                                .sorted(Comparator.comparing(PledgeState::name))
                                .toList(),
                filter.rewardTiers().isEmpty()
                        ? null
                        : filter.rewardTiers().stream().sorted().toList(),
                filter.countries().isEmpty()
                        ? null
                        : filter.countries().stream().sorted().toList(),
                filter.term());
    }

    /** A campaign's segments, newest first. */
    public static List<BackerSegmentResponse> of(List<BackerSegment> segments) {
        return segments.stream().map(BackerSegmentResponse::of).toList();
    }
}
