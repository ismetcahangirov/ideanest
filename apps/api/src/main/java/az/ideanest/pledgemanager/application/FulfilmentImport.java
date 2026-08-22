package az.ideanest.pledgemanager.application;

import java.util.List;

/**
 * What one tracking import did — §4.8's PM-20.
 *
 * <p><strong>Rows that failed are reported, not rolled back.</strong> A creator
 * uploading four thousand parcels with three bad rows needs the three thousand nine
 * hundred and ninety-seven applied and the three named; refusing the file would send
 * them to find three typos with no help, and every subsequent attempt would fail on
 * whichever typo they had not found yet.
 *
 * <p>The corollary is that a caller must read this. A 200 here does not mean the file
 * was clean — {@link #failed()} is how many parcels the creator still has to deal
 * with, and it is on the response for that reason rather than only in the log.
 *
 * @param rows how many data rows were read, not counting the header
 * @param changed how many fulfilments this import created or altered
 * @param unchanged how many rows said exactly what the platform already held.
 *     Reported rather than folded into {@code changed} because re-uploading last
 *     week's file with fifty new lines is the ordinary way this endpoint is used,
 *     and "3,950 unchanged, 50 updated" is the sentence that tells a creator it
 *     worked
 * @param failed how many rows were not applied
 * @param errors one per failed row, bounded — see
 *     {@link az.ideanest.pledgemanager.PledgeManagerProperties.Fulfilment#maxReportedErrors()}.
 *     A creator with a broken column has four thousand identical failures and needs
 *     to see the first few of them, not a response the size of the file they sent
 * @param truncated whether the file was longer than the row cap and the tail was not
 *     read. §4.7's CD-11 argues why this is on the response: a fulfilment list
 *     missing its tail looks exactly like a complete one
 */
public record FulfilmentImport(
        int rows, int changed, int unchanged, int failed, List<RowFailure> errors, boolean truncated) {

    public FulfilmentImport {
        errors = List.copyOf(errors);
    }

    /**
     * One row the import would not apply.
     *
     * @param line which line of the file it was, counting the header as 1. What a
     *     creator can actually find; a pledge identifier is not
     * @param pledgeId as the file gave it, or null when the cell was empty. Echoed
     *     rather than parsed so a creator can search their spreadsheet for the value
     *     they typed
     * @param code §10.4's vocabulary, so a client can group failures rather than
     *     printing four thousand sentences
     * @param message what to do about it, in the creator's terms
     */
    public record RowFailure(int line, String pledgeId, String code, String message) {
    }
}
