package az.ideanest.pledge.api;

import az.ideanest.pledge.application.BackerFilter;
import jakarta.validation.Valid;
import java.util.UUID;

/**
 * "Export these backers."
 *
 * <p><strong>A POST with a body rather than a GET with a query string</strong>, which is
 * §10.2's spelling of this route and is not arbitrary. Three reasons, in order of weight:
 * the filter is the same nested shape a segment stores and a query string flattens it into
 * repeated keys; the export is an audited action and a GET that writes an audit row is a
 * GET that is not safe to retry or prefetch; and a browser will happily put a GET URL in
 * history and in a proxy log, where this one would sit beside "downloaded every backer's
 * email address".
 *
 * @param segmentId a saved segment to export, or null to use {@link #filter()}. When both
 *     are given the segment wins — the client has described the same thing twice and the
 *     saved definition is the one the creator can see and edit
 * @param filter which backers, or null for the whole campaign
 */
public record ExportBackersRequest(UUID segmentId, @Valid BackerFilterBody filter) {

    /** The filter this request describes, defaulting to the whole campaign. */
    public BackerFilter toFilter() {
        return filter == null ? BackerFilter.ANY : filter.toFilter();
    }
}
