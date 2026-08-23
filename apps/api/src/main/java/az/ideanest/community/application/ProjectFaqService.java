package az.ideanest.community.application;

import az.ideanest.community.domain.ProjectFaq;
import az.ideanest.community.infrastructure.ProjectFaqRepository;
import az.ideanest.project.application.CapabilityNotGrantedException;
import az.ideanest.project.application.ProjectAccess;
import az.ideanest.project.application.ProjectNotFoundException;
import az.ideanest.project.application.PublicProjects;
import az.ideanest.shared.access.ProjectCapability;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The FAQ tab: what is on it, and who may change it. §4.4's FAQ tab and §4.7's CD-15.
 *
 * <h2>Authorisation</h2>
 *
 * <p><strong>Writing</strong> asks for {@link ProjectCapability#MANAGE_FAQ} and for
 * nothing else. It is a capability of its own rather than a corner of
 * {@code EDIT_BASICS} because {@code ProjectAccess} records what a coarse check on a
 * published surface costs — the analytics module found the only question it could reach,
 * asked it, and the referral report became readable by anybody granted any editing
 * capability. A coarse escape hatch on a published surface will be taken.
 *
 * <p><strong>Reading is a different question and stays coarse on purpose.</strong>
 * {@link #isTeamMember} decides whether the caller may see the tab of a campaign the
 * public cannot, not whether they may act, and the honest form of that is "does this
 * account work on this campaign" — for which any editing capability is the right answer.
 * Narrowing it to {@code MANAGE_FAQ} would hide a campaign's own FAQ from the person
 * writing its story.
 *
 * <p><strong>Every method here loads through {@link ProjectAccess} before it touches the
 * table</strong>, so there is no path that writes an entry without having been
 * authorised for the campaign it belongs to. Nothing comes back from that call: a
 * campaign is another module's entity and this one may not name it, which
 * {@code ModuleBoundaryTests} enforces.
 *
 * <h2>What is not here</h2>
 *
 * <p><strong>No audit row.</strong> {@code AuditLog} records privileged actions, and
 * publishing an update is one because §5.5 makes it an obligation and no endpoint takes
 * it back. An FAQ entry is the opposite: it is ordinary campaign content, editable and
 * removable by the person who wrote it, and the reward module — the closest analogue,
 * and the one that decides what a backer is promised and what it costs — records
 * nothing either. Auditing this would make the audit table a change log of a text box,
 * which is how a table nobody can read becomes a table nobody checks.
 *
 * <p><strong>No campaign-state rule.</strong> §5.3 freezes the goal, the duration and
 * the reward prices at launch; it says nothing about the FAQ, and it should not — the
 * questions a campaign has to answer arrive after it goes live, which is the whole
 * purpose of the tab. So an entry can be written, edited and removed in any state, and
 * this service asks {@code ProjectAccess} for the capability rather than for
 * {@code EditLocks}.
 */
@Service
public class ProjectFaqService {

    /**
     * The most entries one campaign may have. See {@link TooManyFaqsException}: this is
     * what makes the unpaged public read a bounded response rather than a hope.
     */
    static final int MAX_ENTRIES = 50;

    /** Where a reorder starts counting, and where a campaign's first entry lands. */
    private static final int FIRST_SORT_ORDER = 0;

    private final ProjectFaqRepository faqs;
    private final ProjectAccess access;
    private final PublicProjects publicProjects;

    public ProjectFaqService(ProjectFaqRepository faqs, ProjectAccess access, PublicProjects publicProjects) {
        this.faqs = faqs;
        this.access = access;
        this.publicProjects = publicProjects;
    }

    /**
     * The campaign's FAQ tab, in the creator's order.
     *
     * <p>The team is asked about <em>before</em> {@link PublicProjects} is, and the order
     * matters: a creator reading the FAQ on their own unlaunched campaign is entitled to
     * it, and a campaign that is not publicly visible answers 404 to everybody else.
     * Asking the public question first would have hidden a draft's FAQ from the person
     * writing it.
     *
     * <p>Every entry, with no cursor. §10.2 gives this read none, and
     * {@link #MAX_ENTRIES} is what keeps that from being an unbounded response.
     *
     * @param viewerId the authenticated caller, or null. This endpoint is public — §10.2
     *     lists it under "Project — public" — so most callers have no identity at all
     * @throws ProjectNotFoundException for a campaign that does not exist and for one
     *     that is not publicly visible, identically, to a caller who is not on its team
     */
    @Transactional(readOnly = true)
    public FaqList list(UUID projectId, UUID viewerId) {
        boolean team = isTeamMember(projectId, viewerId);
        if (!team) {
            publicProjects.requireVisible(projectId);
        }
        return new FaqList(faqs.findByProjectIdOrderBySortOrderAscCreatedAtAsc(projectId), team);
    }

    /**
     * Adds an entry to the end of the campaign's list.
     *
     * <p>At the end rather than at a position the client chose: a new question has no
     * claim on a place in an order the creator arranged, and letting a create body carry
     * one would be a second way to reorder that does not rewrite the other rows.
     *
     * @param accountId the authenticated caller, never a value from a request body
     * @throws ProjectNotFoundException when there is no such campaign, and when this
     *     caller has no relationship to it — deliberately the same answer
     * @throws CapabilityNotGrantedException when the caller works on the campaign and was
     *     not granted {@link ProjectCapability#MANAGE_FAQ}
     * @throws TooManyFaqsException when the campaign already holds {@link #MAX_ENTRIES}
     */
    @Transactional
    public ProjectFaq add(UUID projectId, UUID accountId, NewFaq command) {
        access.requireCapability(projectId, accountId, ProjectCapability.MANAGE_FAQ);

        List<ProjectFaq> existing = faqs.findByProjectIdOrderBySortOrderAscCreatedAtAsc(projectId);
        if (existing.size() >= MAX_ENTRIES) {
            throw new TooManyFaqsException(MAX_ENTRIES);
        }

        // One past the last, from the list already read. Positions are not unique, so a
        // tie here would be resolved by created_at and the entry would still land last —
        // this simply keeps the stored order and the read order the same number.
        int position = existing.isEmpty() ? FIRST_SORT_ORDER : existing.getLast().getSortOrder() + 1;

        return faqs.save(ProjectFaq.write(projectId, command.question(), command.answer(), position));
    }

    /**
     * Applies a partial edit to one entry.
     *
     * <p>Addressed by its own identifier on a flat path, so the campaign has to be found
     * before the decision can be asked for — and an entry under a campaign this caller
     * has no part in is answered exactly as an identifier that never existed. See
     * {@link FaqNotFoundException} for why that translation is not evasiveness.
     */
    @Transactional
    public ProjectFaq edit(UUID faqId, UUID accountId, FaqPatch patch) {
        ProjectFaq faq = requireManageable(faqId, accountId);

        // Through the entity, so that FaqContent sees every edit. A patch that mentions
        // neither field is a request that changes nothing, which is not an error: it is
        // what an autosave sends when the creator moved the cursor and typed nothing.
        patch.question().ifPresent(faq::rephrase);
        patch.answer().ifPresent(faq::answerWith);
        return faq;
    }

    /**
     * Removes an entry.
     *
     * <p><strong>A hard delete, and no gap is closed.</strong> §7.3's soft delete
     * protects rows somebody else has relied on — a comment somebody replied to, a
     * pledge. Nothing references an FAQ entry, and a withdrawn answer that the page still
     * had to remember to filter out would be the trap {@code project_updates} describes:
     * the first query that forgets the predicate is the one that serves it. The positions
     * of the remaining entries are left alone because they are read in order rather than
     * by value, so a gap at position 3 changes nothing a reader can see.
     */
    @Transactional
    public void remove(UUID faqId, UUID accountId) {
        faqs.delete(requireManageable(faqId, accountId));
    }

    /**
     * Puts the campaign's FAQ entries in the order given.
     *
     * <p><strong>Every entry, exactly once, or nothing.</strong> See
     * {@link FaqOrderIncompleteException}: a partial list leaves the entries it omits at
     * the positions they already had, so they interleave with the ones that moved and the
     * creator gets an order nobody asked for.
     *
     * <p>Positions are rewritten from zero rather than adjusted. Renumbering the whole
     * list makes the stored order exactly the list the client sent, so two clients that
     * reorder concurrently produce one of the two orders rather than a blend of both.
     * This is {@code RewardService#reorder}, on a different list.
     */
    @Transactional
    public List<ProjectFaq> reorder(UUID projectId, UUID accountId, List<UUID> faqIds) {
        access.requireCapability(projectId, accountId, ProjectCapability.MANAGE_FAQ);

        List<ProjectFaq> entries = faqs.findByProjectIdOrderBySortOrderAscCreatedAtAsc(projectId);
        Map<UUID, ProjectFaq> byId =
                entries.stream().collect(Collectors.toMap(ProjectFaq::getId, Function.identity()));

        List<UUID> requested = faqIds == null ? List.of() : faqIds;
        List<UUID> unexpected = new ArrayList<>();
        Set<UUID> seen = new LinkedHashSet<>();
        for (UUID faqId : requested) {
            // A repeat is as wrong as a stranger: it would give one entry two positions
            // and leave another with none.
            if (faqId == null || !byId.containsKey(faqId) || !seen.add(faqId)) {
                unexpected.add(faqId);
            }
        }
        List<UUID> missing =
                entries.stream().map(ProjectFaq::getId).filter(id -> !seen.contains(id)).toList();

        if (!missing.isEmpty() || !unexpected.isEmpty()) {
            throw new FaqOrderIncompleteException(missing, unexpected);
        }

        List<ProjectFaq> reordered = new ArrayList<>();
        int position = FIRST_SORT_ORDER;
        for (UUID faqId : requested) {
            ProjectFaq faq = byId.get(faqId);
            faq.moveTo(position++);
            reordered.add(faq);
        }
        return reordered;
    }

    /**
     * The entry, if this account may manage the campaign's FAQ.
     *
     * <p>Loading and checking are one call, as in {@link ProjectAccess}: nothing here
     * hands back an entry that has not been authorised for, so a method added later
     * cannot load one and forget to ask.
     */
    private ProjectFaq requireManageable(UUID faqId, UUID accountId) {
        ProjectFaq faq = faqs.findById(faqId).orElseThrow(() -> new FaqNotFoundException(faqId));
        try {
            access.requireCapability(faq.getProjectId(), accountId, ProjectCapability.MANAGE_FAQ);
        } catch (ProjectNotFoundException e) {
            // A stranger is told the entry does not exist, not that the campaign does
            // not. Anything else is an oracle for whether an identifier is real.
            throw new FaqNotFoundException(faqId);
        }
        return faq;
    }

    /**
     * Whether this caller works on the campaign.
     *
     * <p>Asked of {@link ProjectAccess}, which is the one place in the service where that
     * question is answered, and the refusals are turned into {@code false} rather than
     * propagated: on the read path "you are not on the team" is not an error, it is the
     * ordinary case that decides whether the campaign has to be publicly visible.
     */
    private boolean isTeamMember(UUID projectId, UUID accountId) {
        if (accountId == null) {
            return false;
        }
        try {
            access.requireEditable(projectId, accountId);
            return true;
        } catch (ProjectNotFoundException | CapabilityNotGrantedException e) {
            // A stranger, a revoked collaborator, and a collaborator granted only
            // VIEW_FINANCES all land here, and all of them read the tab as the public
            // does.
            return false;
        }
    }
}
