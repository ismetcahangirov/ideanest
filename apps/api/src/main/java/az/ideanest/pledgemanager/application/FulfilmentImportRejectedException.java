package az.ideanest.pledgemanager.application;

/**
 * The tracking file could not be read at all — §4.8's PM-20.
 *
 * <p><strong>Not one row's failure.</strong> A row that names a pledge on another
 * campaign, or a status nobody recognises, is reported back beside the row number and
 * the rest of the file is still applied — a creator with four thousand parcels and
 * three typos must not be sent away with nothing. This is for the faults that are
 * properties of the document: no header, no {@code pledge_id} column, nothing under
 * the header.
 *
 * <p>Carries its own code because the three cases need three different corrections
 * and a client that showed one message for all of them would send a creator to check
 * the wrong thing.
 */
public class FulfilmentImportRejectedException extends RuntimeException {

    private final String code;

    public FulfilmentImportRejectedException(String code, String message) {
        super(message);
        this.code = code;
    }

    /** §10.4's vocabulary: what a client switches on. */
    public String code() {
        return code;
    }
}
