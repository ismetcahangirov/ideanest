package az.ideanest.reward.application;

import az.ideanest.project.application.ProjectAccess;
import az.ideanest.project.application.ProjectNotFoundException;
import az.ideanest.reward.domain.Item;
import az.ideanest.reward.domain.RewardTier;
import az.ideanest.reward.infrastructure.ItemRepository;
import az.ideanest.reward.infrastructure.RewardTierRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Whether this account may touch this item or this tier.
 *
 * <p><strong>It decides nothing.</strong> Every answer comes from
 * {@link ProjectAccess}, which is the one place in the service where "who may edit
 * this campaign" is settled — and which #38 widens to collaborators by changing that
 * one file. What this class does is the other half of the question: an item and a
 * tier are addressed by their own identifiers, so something has to find the campaign
 * they belong to before that decision can be asked for. Writing that lookup inline in
 * eight endpoints is eight places for one of them to load a row and forget to ask.
 *
 * <p><strong>Loading and checking are one call</strong>, as in {@link ProjectAccess}:
 * each method returns the row, so there is no way to obtain one without having been
 * authorised for it.
 *
 * <p>The project's return value from {@code requireEditable} is deliberately
 * discarded. {@code Project} lives in another module's domain package, which this
 * module may not name — {@code ModuleBoundaryTests} enforces it — and nothing here
 * needs the campaign itself. What it needs is the refusal.
 */
@Service
public class RewardAccess {

    private final ProjectAccess projects;
    private final ItemRepository items;
    private final RewardTierRepository rewards;

    public RewardAccess(ProjectAccess projects, ItemRepository items, RewardTierRepository rewards) {
        this.projects = projects;
        this.items = items;
        this.rewards = rewards;
    }

    /** Refuses unless this account may edit the campaign. Throws {@code ProjectNotFoundException} if not. */
    public void requireEditableProject(UUID projectId, UUID accountId) {
        projects.requireEditable(projectId, accountId);
    }

    /**
     * The item, if this account may edit the campaign that owns it.
     *
     * <p>An item under somebody else's campaign is answered exactly as an
     * identifier that never existed. The translation below is what makes that true:
     * without it a stranger would get {@code PROJECT_NOT_FOUND} for a real item and
     * {@code ITEM_NOT_FOUND} for an invented one, which is an oracle for whether an
     * identifier is real. See {@link ItemNotFoundException}.
     */
    public Item requireEditableItem(UUID itemId, UUID accountId) {
        Item item = items.findById(itemId).orElseThrow(() -> new ItemNotFoundException(itemId));
        try {
            projects.requireEditable(item.getProjectId(), accountId);
        } catch (ProjectNotFoundException e) {
            throw new ItemNotFoundException(itemId);
        }
        return item;
    }

    /** The tier, if this account may edit the campaign that owns it. Same reasoning as above. */
    public RewardTier requireEditableReward(UUID rewardId, UUID accountId) {
        RewardTier reward = rewards.findById(rewardId).orElseThrow(() -> new RewardNotFoundException(rewardId));
        try {
            projects.requireEditable(reward.getProjectId(), accountId);
        } catch (ProjectNotFoundException e) {
            throw new RewardNotFoundException(rewardId);
        }
        return reward;
    }
}
