package az.ideanest.community.application;

import az.ideanest.community.domain.ProjectFaq;
import java.util.List;

/**
 * A campaign's whole FAQ tab, and who is looking at it.
 *
 * @param entries in the creator's order. All of them: §10.2 gives this read no cursor —
 *     see {@code PublicProjectFaqController} for why, and for what happens if that stops
 *     being the right answer
 * @param forTeam whether the caller works on the campaign. It travels with the list
 *     because the response's cache policy depends on it, and deriving it a second time
 *     in the controller would be a second place to get it wrong. The <em>contents</em>
 *     do not depend on it — an FAQ entry has no visibility, and the team sees exactly
 *     what a visitor sees — but the team can read the tab of a campaign that is not
 *     publicly visible at all, and a shared cache handed that body would serve an
 *     unlaunched campaign's FAQ to the next stranger who asked
 */
public record FaqList(List<ProjectFaq> entries, boolean forTeam) {

    public FaqList {
        entries = List.copyOf(entries);
    }
}
