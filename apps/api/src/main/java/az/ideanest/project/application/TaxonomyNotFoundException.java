package az.ideanest.project.application;

import java.util.UUID;

/**
 * No category or subcategory at that identifier - issue #309.
 *
 * <p>404. One exception for both, because the console addresses them through different
 * paths and a caller who reaches the wrong one has made the same mistake either way.
 */
public class TaxonomyNotFoundException extends RuntimeException {

    public TaxonomyNotFoundException(UUID id) {
        super("No taxonomy entry " + id);
    }
}
