package az.ideanest.pledge.application;

import az.ideanest.pledge.domain.Pledge;
import az.ideanest.pledge.domain.PledgeAddon;
import java.util.List;

/**
 * A pledge and the add-on lines that belong to it, read together.
 *
 * <p>Two rows in two tables are one thing to a client, and assembling them here
 * rather than in the controller is what keeps the read inside a transaction:
 * {@code open-in-view} is off, so a response built from a lazily loaded association
 * outside the service layer is a response built from a closed session.
 *
 * <p>The entities themselves rather than a copy of their fields. The boundary this
 * respects is the module one — {@code ModuleBoundaryTests} lets a module's
 * {@code api} package see its own {@code domain} — and interposing a second set of
 * records between the entity and the response would be a third place for the six
 * amounts to be listed and a third place to get one of them wrong.
 */
public record PledgeDetail(Pledge pledge, List<PledgeAddon> addons) {

    public PledgeDetail {
        addons = addons == null ? List.of() : List.copyOf(addons);
    }
}
