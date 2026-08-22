package az.ideanest.pledgemanager.api;

import az.ideanest.pledgemanager.application.AddressCollectionProgress;

/**
 * How far a campaign has got with collecting addresses.
 *
 * <p>Counts and nothing else — no address, no backer, nothing decrypted. That is what
 * makes it the read a dashboard can poll and the one endpoint in this feature whose
 * response is not sensitive.
 */
public record AddressProgressResponse(long given, long locked, long editable) {

    public static AddressProgressResponse of(AddressCollectionProgress progress) {
        return new AddressProgressResponse(progress.given(), progress.locked(), progress.editable());
    }
}
