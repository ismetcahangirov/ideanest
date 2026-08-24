package az.ideanest.project.application;

/**
 * A category or subcategory already uses that handle - issue #309.
 *
 * <p>409. Detected from the unique index rather than by reading first, which is what makes
 * two administrators adding the same category at once produce one category and one clear
 * refusal instead of two rows or a constraint violation nobody expected.
 *
 * <p>The message names the slug, because it is the one field that cannot be changed
 * afterwards - so being told which value collided is what lets somebody pick another
 * before committing to it.
 */
public class TaxonomySlugTakenException extends RuntimeException {

    private final transient String slug;

    public TaxonomySlugTakenException(String slug) {
        super("The handle " + slug + " is already in use");
        this.slug = slug;
    }

    public String slug() {
        return slug;
    }
}
