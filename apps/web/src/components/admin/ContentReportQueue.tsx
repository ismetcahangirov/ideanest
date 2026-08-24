'use client';

import { useState } from 'react';
import { InlineAlert, Pill } from '@ideanest/ui';
import { ModerationQueue } from '../moderation/ModerationQueue';
import type { ReportTargetType } from '../../lib/moderation/api';

/** The two halves of AD-09's content queue, and what each is called on the screen. */
const SURFACES: ReadonlyArray<readonly [ReportTargetType, string]> = [
  ['COMMENT', 'Comments'],
  ['PROJECT_UPDATE', 'Updates'],
];

/**
 * §4.11's AD-09: complaints about what people wrote — issue #297.
 *
 * <h2>What was actually blocking this</h2>
 *
 * #297 said updates had no report route, "so half the queue has no intake" — and the
 * taxonomy agreed: `ReportTargetType.PROJECT_UPDATE` carried a comment saying nothing could
 * write it, because `project_updates` did not exist.
 *
 * Both stopped being true. #83 built the table, and #297 added the check, the
 * `ReportTargets` branch and `POST /v1/updates/{id}/report` — with no migration at all,
 * because V23&apos;s constraint had named the value since #102. That bet has now paid off
 * twice: comments arrived the same way with #84.
 *
 * <h2>Two chips over one queue, not two pages</h2>
 *
 * `/admin/moderation/profiles` is a page of its own because a complaint about a person leads
 * to a different decision — a ban revokes every session somebody holds. Comments and updates
 * do not: both are things somebody wrote on a campaign, both are decided with the same two
 * outcomes, and a moderator working one is working the other in the same sitting. Splitting
 * them across two routes would mean checking two queues to find out whether there is
 * anything to do.
 *
 * <p>The chip is a real filter rather than a browser-side narrowing, which is the correction
 * #298 made: it changes the query the service is asked, so paging continues within the
 * surface rather than running out at the end of whichever twenty-five rows were loaded.
 *
 * <h2>Deciding a report does not remove what was reported</h2>
 *
 * The same note the profile queue carries, and it is here for the same reason: it is the
 * thing moderators get wrong. Upholding a complaint about a comment records a judgement;
 * taking the comment down is a separate action on the campaign, by somebody who can see it
 * in context.
 */
export function ContentReportQueue() {
  const [surface, setSurface] = useState<ReportTargetType>('COMMENT');

  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-wrap items-center gap-2">
        {SURFACES.map(([target, label]) => (
          <Pill
            key={target}
            variant={surface === target ? 'outline' : 'ghost'}
            size="sm"
            aria-pressed={surface === target}
            onClick={() => setSurface(target)}
          >
            {label}
          </Pill>
        ))}
      </div>

      {surface === 'PROJECT_UPDATE' && (
        <InlineAlert variant="info" title="Updates became reportable with #297">
          Nothing could file a complaint about an update until this release, so this queue
          starts empty on every deployment and fills from here. Reports filed before it are
          about comments and campaigns, and they are on the other chips.
        </InlineAlert>
      )}

      {/*
        Keyed on the surface so that switching chips remounts the queue rather than
        reconciling one target's rows into another's. Without the key the first paint after a
        switch shows comments under a heading that says updates, for exactly as long as the
        request takes -- which on a moderation queue is long enough to act on.
      */}
      <ModerationQueue key={surface} pinnedTarget={surface} detailHrefBase="/admin/moderation" />
    </div>
  );
}
