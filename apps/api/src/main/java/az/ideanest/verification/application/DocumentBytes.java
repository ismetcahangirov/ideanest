package az.ideanest.verification.application;

import java.util.Optional;

/**
 * What a submitted file actually is — §17.3's "magic-byte validation, never by file
 * extension", issue #105.
 *
 * <h2>Why the client's word is not taken</h2>
 *
 * <p>A {@code Content-Type} on a multipart part is a string the client chose, and so is the
 * filename. Both are the wrong thing to trust for a reason that has nothing to do with
 * malice being likely: a browser guesses the type from the extension, so a photograph saved
 * as {@code .jpg} that is really a HEIC arrives labelled JPEG and is stored as one — and
 * the reviewer opens a file their viewer cannot render.
 *
 * <p>The malicious case matters too and is simpler: the bytes are handed back to a member
 * of staff over HTTP, and a stored {@code text/html} served with the type its uploader
 * chose is a script running on the console's origin. Deciding the type from the content is
 * what makes {@code Content-Disposition: attachment} on the way out sufficient rather than
 * hopeful.
 *
 * <h2>Three formats and no more</h2>
 *
 * <p>JPEG and PNG are what a phone camera produces; PDF is what a company registry emails.
 * Everything else — HEIC, WebP, TIFF, a Word document — is refused with a message naming
 * the three, because a reviewer's browser has to be able to render whatever is accepted and
 * this platform has no transcoder (§13.1's ingestion is not built).
 */
public final class DocumentBytes {

    private DocumentBytes() {}

    /** JPEG: {@code FF D8 FF}. Every variant shares it. */
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};

    /** PNG: the eight-byte signature, including the CRLF pair that detects text-mode transfer. */
    private static final byte[] PNG = {
        (byte) 0x89, 'P', 'N', 'G', (byte) 0x0D, (byte) 0x0A, (byte) 0x1A, (byte) 0x0A
    };

    /** PDF: {@code %PDF-}. */
    private static final byte[] PDF = {'%', 'P', 'D', 'F', '-'};

    /** The media type these bytes really are, or empty when they are none of the three. */
    public static Optional<String> mediaTypeOf(byte[] content) {
        if (content == null) {
            return Optional.empty();
        }
        if (startsWith(content, JPEG)) {
            return Optional.of("image/jpeg");
        }
        if (startsWith(content, PNG)) {
            return Optional.of("image/png");
        }
        if (startsWith(content, PDF)) {
            return Optional.of("application/pdf");
        }
        return Optional.empty();
    }

    private static boolean startsWith(byte[] content, byte[] signature) {
        if (content.length < signature.length) {
            return false;
        }
        for (int index = 0; index < signature.length; index++) {
            if (content[index] != signature[index]) {
                return false;
            }
        }
        return true;
    }
}
