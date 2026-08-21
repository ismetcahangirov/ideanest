package az.ideanest.community.application;

/**
 * There is no account at that slug, or there no longer is one.
 *
 * <p>A 404, and deliberately the same answer for both — an account closed under §17.4 is not
 * findable, and an endpoint that distinguished "never existed" from "left" would answer a
 * question about somebody who has asked to be forgotten.
 */
public class FollowTargetNotFoundException extends RuntimeException {

    private final String slug;

    public FollowTargetNotFoundException(String slug) {
        super("No account at " + slug);
        this.slug = slug;
    }

    /**
     * The slug that was asked for.
     *
     * <p>Safe to echo, unlike a cursor: it came from the path of a public profile route, it is
     * the thing the caller typed into the address bar, and a 404 that does not name it is
     * indistinguishable from a routing fault.
     */
    public String slug() {
        return slug;
    }
}
