package az.ideanest.shared;

import com.github.f4b6a3.uuid.UuidCreator;
import java.util.UUID;

/**
 * Primary keys for every table in the service.
 *
 * <p>UUID version 7: a millisecond timestamp followed by randomness. Two
 * properties matter here.
 *
 * <p>It is <strong>sortable</strong>, so rows inserted in sequence land next to
 * each other in the index. Version 4 scatters them, which turns a B-tree into a
 * write-amplification machine once the table no longer fits in cache — the
 * reason large tables keyed by random UUIDs get slower in a way nobody can
 * attribute to any one query.
 *
 * <p>It <strong>leaks no volume</strong>. A sequence in a URL tells a competitor
 * how many users signed up this month and tells anyone how many exist at all.
 *
 * <p>Identifiers are generated here rather than by the database so that an
 * aggregate has its identity before it is persisted, which is what lets code
 * build an object graph and save it in one transaction.
 */
public final class Identifiers {

    private Identifiers() {
    }

    public static UUID newIdentifier() {
        return UuidCreator.getTimeOrderedEpoch();
    }
}
