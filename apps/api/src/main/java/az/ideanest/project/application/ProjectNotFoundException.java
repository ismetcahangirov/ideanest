package az.ideanest.project.application;

import java.util.UUID;

/**
 * No such campaign, or none this caller is allowed to know about.
 *
 * <p><strong>The two cases are deliberately one exception.</strong> A draft is
 * confidential until it launches: an unreleased product, a price nobody has been
 * told, sometimes a company that does not exist yet. Answering 403 for a campaign
 * that exists and 404 for one that does not turns the editor into an oracle for
 * whether a competitor has something in progress, which is exactly the
 * information a draft is private to protect.
 *
 * <p>#38 adds collaborators, and with them the first case where a caller may see
 * a campaign and still be refused an action on it. That one is a 403 — there is
 * nothing left to hide from somebody who was invited — and it belongs to a
 * separate exception rather than to this one.
 */
public class ProjectNotFoundException extends RuntimeException {

    public ProjectNotFoundException(UUID projectId) {
        // The identifier and nothing else. This message reaches a log line, and
        // the title of an unlaunched campaign is the confidential part.
        super("No project " + projectId + " is visible to this caller");
    }

    /**
     * The same refusal for a campaign addressed by its public URL rather than by its
     * identifier — §10.2's {@code /v1/projects/{creatorSlug}/{projectSlug}}.
     *
     * <p>The two slugs are already in the request line, so naming them here reveals
     * nothing the caller did not send. What is still withheld is which half was wrong: a
     * message distinguishing "no such creator" from "that creator has no such campaign"
     * would answer, for anybody willing to guess, whether a creator has something in
     * progress under a name they have not announced.
     */
    public ProjectNotFoundException(String creatorSlug, String projectSlug) {
        super("No project " + creatorSlug + "/" + projectSlug + " is visible to this caller");
    }
}
