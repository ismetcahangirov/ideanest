package az.ideanest.pledge.api;

import az.ideanest.pledge.application.BackerArchive;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * One page of the caller's own pledges — §10.2's {@code GET /v1/me/pledges}.
 *
 * <p><strong>{@code nextCursor} is null on the last page rather than absent or
 * empty.</strong> The convention {@code SavedListResponse} sets and the reason it gives: a
 * client tests one thing, and a three-way distinction between null, missing and {@code ""}
 * is what gets handled two ways in two clients.
 *
 * <p><strong>No {@code ETag} and no {@code Cache-Control} beyond the controller's refusal
 * to be cached at all.</strong> §10.3 asks for both on a <em>public</em> read; this is a
 * backer's own pledges behind a bearer token, and a validator on it would be a value a
 * shared cache could key somebody's private list by. {@code BackerSignalController} says
 * the same of {@code GET /v1/me/saved}.
 *
 * @param pledges the rows, newest first
 * @param nextCursor the opaque value to pass back as {@code ?cursor=}, or null at the end
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record BackerPledgeListResponse(List<BackerPledgeSummary> pledges, String nextCursor) {

    public static BackerPledgeListResponse of(BackerArchive.PledgePage page) {
        return new BackerPledgeListResponse(
                page.pledges().stream().map(BackerPledgeSummary::of).toList(),
                page.next() == null ? null : page.next().encode());
    }
}
