package az.ideanest.verification.application;

/**
 * This deployment cannot store a document at all — issue #105.
 *
 * <p>No encryption key is configured, and {@code VerificationProperties} argues why there
 * is no default one: a key generated at start-up changes on the next deploy and a key in
 * the repository is published.
 *
 * <p>It answers <strong>503 and not 500</strong>. A creator whose upload failed with a
 * server error tries again, and again; one told the service is not accepting documents
 * stops and asks somebody. The distinction is the whole reason this is its own exception.
 */
public class DocumentStorageUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DocumentStorageUnavailableException() {
        super("Document submission is not configured on this deployment");
    }
}
