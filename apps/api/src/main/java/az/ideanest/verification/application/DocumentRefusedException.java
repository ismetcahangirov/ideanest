package az.ideanest.verification.application;

/**
 * A submitted document the platform will not store — issue #105.
 *
 * <p>One exception with a code rather than five classes, because every one of these is the
 * same conversation with the creator — "that file cannot be used, here is why" — and a
 * client rendering them needs one branch with a message per code, not five.
 *
 * <p><strong>The message never repeats anything from the file.</strong> Not the filename,
 * not a byte of it. A problem detail is a document a client may log, and the file in
 * question is somebody's passport.
 */
public class DocumentRefusedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** What is wrong with it, as the problem detail's {@code code}. */
    public enum Reason {
        /** No file, or an empty one. */
        EMPTY,
        /** Larger than {@code ideanest.verification.documents.max-bytes}. */
        TOO_LARGE,
        /** Not a JPEG, a PNG or a PDF, whatever the client called it. */
        UNSUPPORTED_TYPE,
        /** This kind is not one the subject may submit — a company cannot show a passport. */
        WRONG_KIND_FOR_SUBJECT,
        /** The submission already holds as many documents as it may. */
        TOO_MANY
    }

    private final transient Reason reason;

    public DocumentRefusedException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
