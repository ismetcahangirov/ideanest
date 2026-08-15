package az.ideanest.project.application;

import java.util.UUID;

/**
 * A story version number that does not exist for this campaign.
 *
 * <p>Distinct from {@link ProjectNotFoundException} because the caller has
 * already been authorised for the campaign by the time this can be thrown, so
 * there is nothing to conceal: the honest answer is that this number is gone,
 * and the reason is almost always retention. Fifty versions is a day and a half
 * of editing, and a creator following a link they kept from last week should be
 * told the draft was pruned rather than that their campaign does not exist.
 */
public class StoryVersionNotFoundException extends RuntimeException {

    private final int number;

    public StoryVersionNotFoundException(UUID projectId, int number) {
        super("Project " + projectId + " has no story version " + number);
        this.number = number;
    }

    public int number() {
        return number;
    }
}
