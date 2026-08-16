package az.ideanest.discovery.application;

/**
 * Another collection already answers at that slug.
 *
 * <p>409 rather than 400: nothing about the request is malformed, and the reason it
 * cannot be carried out is the state of the platform rather than the shape of the
 * input. The distinction matters to a client, which retries a 409 with a different
 * slug and does not retry a 400 at all.
 *
 * <p>Raised before the insert as well as caught behind {@code collections_slug_key},
 * because a curator who typed a slug somebody used last season needs to be told that
 * rather than shown a constraint violation.
 */
public class CollectionSlugTakenException extends RuntimeException {

    private final String slug;

    public CollectionSlugTakenException(String slug) {
        super("A collection already exists at slug " + slug);
        this.slug = slug;
    }

    public String slug() {
        return slug;
    }
}
