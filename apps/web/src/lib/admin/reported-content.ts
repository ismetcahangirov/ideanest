import { authorizedFetch } from '../api/client';
import { errorFrom } from '../api/problem';

/**
 * What a report is about — issue #399.
 *
 * <h2>The read the moderation queues were deciding without</h2>
 *
 * <p>`/admin/moderation/{id}` is the deepest view of a complaint, and for a report about a
 * comment it rendered: the reason, the reporter's claim, an identifier for the reporter, and
 * two irreversible buttons. Counted in the DOM, zero links. Not the comment, not who wrote
 * it, not which campaign it is on, and no route to any of the three.
 *
 * <p>A moderator asked to uphold or dismiss something they cannot read either guesses or
 * leaves the queue to find the content another way — and the cheap guess is always to
 * dismiss, because upholding a report you cannot read is the riskier of the two. That is
 * how a moderation queue stops working while continuing to look like it works.
 *
 * <h2>A third read, and it does not block the other two</h2>
 *
 * <p>The detail screen already loads the report and its audit trail independently, so a
 * trail that fails costs a line of text rather than the screen. This is the third read in
 * that arrangement and degrades the same way: a moderator who cannot load the evidence is
 * told the evidence is missing, and still sees the report.
 *
 * <p>It is not folded into the report's own response because that response is also every row
 * of the queue — assembling the content, its author and its campaign twenty-five times a page
 * is twenty-five lookups nobody reads.
 */

/**
 * What the platform can say about the reported thing.
 *
 * <p>Four values rather than a success flag, because they lead to four different decisions:
 *
 * - `PRESENT` — it is there and it is on the platform.
 * - `REMOVED` — it is there and it has been taken down. **The text still comes back**: the
 *   service keeps a removed comment's row for exactly this, and a moderator told only
 *   "removed" cannot tell an upheld report from a dismissed one. What the screen adds is
 *   that somebody has already handled it, so it is not handled twice.
 * - `GONE` — the identifier names nothing any more. Distinct from `REMOVED` on purpose.
 * - `ADDRESSED_DIRECTLY` — the target is a campaign or an account, which the console reaches
 *   directly: a campaign has a staff preview that renders it in any state, and an account has
 *   a public profile. There is no separate blob of text to inline, and a fragment of a page
 *   next to a link to that page is worse than the link alone.
 */
export type ReportedContentState = 'PRESENT' | 'REMOVED' | 'GONE' | 'ADDRESSED_DIRECTLY';

/** The campaign the content sits on, with both halves of its public path. */
export interface ReportedCampaign {
  id: string;
  title: string;
  slug?: string | null;
  creatorSlug?: string | null;
}

/**
 * The evidence, as the console renders it.
 *
 * <p>Every optional field is `?: T | null` because the service serialises with
 * `default-property-inclusion: non_null` — an absent title is absent from the JSON rather
 * than present and null.
 */
export interface ReportedContent {
  /** `COMMENT`, `PROJECT_UPDATE`, `PROJECT` or `USER` — the report's own target kind. */
  targetType: string;
  state: ReportedContentState;
  /** An update's headline. Absent for a comment, which has none. */
  title?: string | null;
  /**
   * What was written, verbatim.
   *
   * <p><strong>Untrusted.</strong> It is text one member of the public wrote about another,
   * arriving on a screen operated by staff, and it is rendered as text and never as markup.
   */
  body?: string | null;
  authorId?: string | null;
  /** An update's per-campaign sequence — "update 4", which is what people call it. */
  number?: number | null;
  project?: ReportedCampaign | null;
  /** When the content was written, which is not when the report was filed. */
  createdAt?: string | null;
}

/**
 * The evidence behind one report.
 *
 * @throws ApiError 404 when there is no such report. **Not** when the content has gone —
 *     that is a 200 carrying `GONE`, because "there is no such report" and "the comment it
 *     was about has been purged" send a moderator to two different places
 */
export async function readReportedContent(
  reportId: string,
  signal?: AbortSignal,
): Promise<ReportedContent> {
  const response = await authorizedFetch(
    `/v1/admin/moderation/reports/${encodeURIComponent(reportId)}/content`,
    { signal },
  );
  if (!response.ok) throw await errorFrom(response);

  return (await response.json()) as ReportedContent;
}
