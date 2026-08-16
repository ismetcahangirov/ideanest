package az.ideanest.discovery.application;

/**
 * There is no collection at that slug that this caller may see.
 *
 * <p><strong>404, and never 403.</strong> A collection that has not been published,
 * one whose window has not opened, and one that never existed are the same answer on
 * purpose. An unpublished collection is an editorial decision in progress — which
 * campaigns the platform is about to put its name behind, and by implication which
 * ones it passed over — and a 403 would confirm to anybody guessing slugs that
 * {@code /collections/spring-2027-open-call} is a thing that exists. That is a
 * commercially interesting fact about a platform's plans, and it is exactly the
 * confidentiality {@code ProjectNotFoundException} protects for a draft campaign.
 *
 * <p>An expired collection is the same answer for a weaker reason: it is simply not
 * there any more, and there is nothing to tell a reader that a 410 would tell them
 * usefully, since a collection can be republished.
 */
public class CollectionNotFoundException extends RuntimeException {

    public CollectionNotFoundException(String slug) {
        super("No visible collection with slug " + slug);
    }
}
