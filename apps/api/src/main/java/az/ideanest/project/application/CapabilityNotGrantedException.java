package az.ideanest.project.application;

import az.ideanest.project.domain.Capability;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The caller works on this campaign and may not do this particular thing.
 *
 * <p><strong>This is the 403 that {@link ProjectNotFoundException} predicted.</strong>
 * That exception answers 404 for a campaign the caller may not know about, because
 * a draft is confidential and distinguishing "not yours" from "not there" turns the
 * editor into an oracle. A collaborator is the case where that reasoning stops
 * applying: they were invited, they have accepted, they can already read the
 * campaign — so refusing them with a 404 would not protect anything and would tell
 * them their own grant had vanished.
 *
 * <p>The response names the capabilities that would have authorised the request and
 * the ones the caller holds. Both, because the useful message is "you need
 * EDIT_REWARDS and you have EDIT_STORY", and neither half is information the caller
 * is not entitled to: it is a description of their own grant.
 *
 * @param projectId which campaign, for the log line
 * @param requiredAnyOf the capabilities that would each have been enough.
 *     <strong>Empty means no capability confers this action</strong> — launching and
 *     cancelling belong to the creator alone — which is a stronger statement than
 *     any set of capabilities could make
 * @param held what the caller's grant actually contains
 */
public class CapabilityNotGrantedException extends RuntimeException {

    private final transient Set<Capability> requiredAnyOf;

    private final transient Set<Capability> held;

    public CapabilityNotGrantedException(
            UUID projectId, Set<Capability> requiredAnyOf, Set<Capability> held) {
        // The identifiers and the capability names, and nothing about the campaign
        // itself: this message reaches a log, and the title of an unlaunched
        // campaign is the confidential part.
        super("Project " + projectId + " requires one of " + requiredAnyOf + "; this caller holds " + held);
        this.requiredAnyOf = requiredAnyOf;
        this.held = held;
    }

    /** Whether this refusal is "the creator only", rather than a missing capability. */
    public boolean isCreatorOnly() {
        return requiredAnyOf.isEmpty();
    }

    public List<String> requiredAnyOf() {
        return names(requiredAnyOf);
    }

    public List<String> held() {
        return names(held);
    }

    private static List<String> names(Set<Capability> capabilities) {
        return capabilities.stream().map(Capability::name).sorted().toList();
    }
}
