package az.ideanest.project.domain;

/**
 * A story document the server will not store.
 *
 * <p>In {@code domain} rather than beside the other refusals in
 * {@code application}, because the validator that throws it is a pure type in
 * {@code domain} and a domain class may not depend on the application layer. It
 * is translated to {@code 400 STORY_DOCUMENT_INVALID} by
 * {@code StoryExceptionHandler}.
 *
 * <p><strong>The message names what was wrong, and {@link #path()} says where.</strong>
 * A story is hundreds of blocks long by the time it is worth publishing, and
 * "the story is invalid" would leave a creator scrolling through it looking for
 * the image whose description they never wrote. The path is a pointer into the
 * document — {@code blocks[7].alt} — which the editor turns into a message
 * beside that block.
 *
 * <p>Not a subclass of {@code ProjectFieldRejectedException}, deliberately: that
 * would make it answer {@code PROJECT_FIELD_INVALID}, and the contract requires
 * a code of its own so that a client can tell a malformed document from a title
 * that is too long without matching on prose.
 */
public class StoryDocumentInvalidException extends RuntimeException {

    private final String path;

    public StoryDocumentInvalidException(String path, String message) {
        super(message);
        this.path = path;
    }

    /** Where in the document the problem is, as {@code blocks[3].id} or {@code blocks}. */
    public String path() {
        return path;
    }
}
