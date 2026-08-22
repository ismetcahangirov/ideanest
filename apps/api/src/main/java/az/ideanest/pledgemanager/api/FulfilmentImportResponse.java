package az.ideanest.pledgemanager.api;

import az.ideanest.pledgemanager.application.FulfilmentImport;
import java.util.List;

/**
 * What one tracking import did — §4.8's PM-20.
 *
 * <p><strong>A 200 does not mean the file was clean.</strong> {@code failed} and
 * {@code errors} are on the success response deliberately: a partly good file is the
 * normal outcome of a spreadsheet that went to a fulfilment partner and came back, and
 * an API that answered 200 with no way to see the refusals would leave a creator
 * believing four thousand parcels had tracking when three hundred did not.
 *
 * @param errors the first few refused rows, each with the line number in the file the
 *     creator uploaded. Bounded — {@code failed} is exact and this list is not
 * @param truncated whether the file was longer than the row cap and its tail was not
 *     read
 */
public record FulfilmentImportResponse(
        int rows, int changed, int unchanged, int failed, List<RowFailureBody> errors, boolean truncated) {

    public static FulfilmentImportResponse of(FulfilmentImport result) {
        return new FulfilmentImportResponse(
                result.rows(),
                result.changed(),
                result.unchanged(),
                result.failed(),
                result.errors().stream().map(RowFailureBody::of).toList(),
                result.truncated());
    }

    /**
     * One row that was not applied.
     *
     * @param line the line of the uploaded file, counting the header as 1. What a
     *     creator can find in their spreadsheet
     * @param pledgeId echoed as it was typed, so a creator can search for the value in
     *     the file rather than for one this platform reformatted
     */
    public record RowFailureBody(int line, String pledgeId, String code, String message) {

        static RowFailureBody of(FulfilmentImport.RowFailure failure) {
            return new RowFailureBody(failure.line(), failure.pledgeId(), failure.code(), failure.message());
        }
    }
}
