'use client';

import { Link } from '../../i18n/navigation';
import { useCallback, useEffect, useRef, useState } from 'react';
import { CircleAlert, CircleCheck, Info } from 'lucide-react';
import { InlineAlert, Pill, ProgressBar, Skeleton, SkeletonGroup } from '@ideanest/ui';
import { ApiError } from '../../lib/api/problem';
import {
  getProjectChecklist,
  submitProject,
  type ChecklistItem,
  type ProjectChecklist,
  type ProjectState,
} from '../../lib/projects/api';
import {
  SECTION_LABEL,
  describeProgress,
  isChecklistSection,
  progressOf,
  sectionHref,
  unmetFromRefusal,
  unmetOf,
  type UnmetRequirement,
} from '../../lib/projects/checklist';
import { EditorShell, PROJECT_STATE_LABEL } from './EditorShell';
import { useProjectEdit } from './useProjectEdit';

/**
 * The review tab: how complete the campaign is, what moderation last said about
 * it, and the control that sends it for review.
 *
 * <h3>THE SERVER IS THE AUTHORITY, NOT THIS SCREEN</h3>
 *
 * The checklist is read when the tab opens. `POST /submit` re-checks the same
 * rules server-side with the same class, so the two normally agree — but the read
 * is a snapshot, a collaborator may have emptied a field since, and a deployment
 * may enforce a rule this build has never heard of. So a refusal is rendered from
 * the requirements the SERVER named and the stale list is replaced, rather than
 * the creator being shown a screen that says everything is fine beside a message
 * that says it is not.
 *
 * That is also why there is no copy of §5.3 here. `basics.ts` validates on the
 * client because a creator typing needs an answer in the same second; nothing on
 * this screen is being typed, and a second implementation of the rules would only
 * be a second thing that could disagree.
 *
 * <h3>BLOCKING AND ADVISORY ARE DISTINGUISHED BY MORE THAN COLOUR</h3>
 *
 * Two headed groups, two icon shapes, and a spoken status on every row
 * (docs/ui-kit.md §9.2). A checklist rendered only in red and green tells a
 * creator with a colour-vision deficiency nothing at all, and tells everybody else
 * that a suggestion is a barrier.
 *
 * <h3>THE SCORE IS A SENTENCE FIRST</h3>
 *
 * The bar is `aria-hidden`: it is a picture of the number that the sentence beside
 * it already carries, and `role="progressbar"` announced alongside "83% complete.
 * 10 of 10 required items done" is the same fact twice. The counts are the part
 * that matters, because they are what says whether the remainder is optional.
 *
 * MOTION: none. `docs/motion-system.md` §5 gives the campaign editor "none —
 * autosave indicator only", and nothing on this tab autosaves.
 */

const LOADING_ROWS = [0, 1, 2, 3];

/**
 * The states from which submitting is an action worth offering.
 *
 * A PRESENTATION DECISION, NOT A RULE. §6.1 is enforced server-side and there is
 * deliberately no client-side transition table; this only decides whether to draw
 * a button, because "Submit for review" under a live campaign is nonsense rather
 * than a refusal worth letting somebody discover. Anything this list is wrong
 * about becomes a 409 the panel renders, which is the same outcome as never having
 * had the list.
 */
const SUBMITTABLE_FROM: readonly ProjectState[] = ['DRAFT', 'PRELAUNCH', 'CHANGES_REQUESTED'];

/** What a campaign in this state is waiting for, said plainly. */
const STATE_NOTE: Partial<Record<ProjectState, string>> = {
  SUBMITTED:
    'This campaign is with our moderators. There is nothing for you to do while it is in review, and you will be told the outcome. You can keep editing in the meantime.',
  APPROVED:
    'Moderation has cleared this campaign. It goes live when you launch it, or at the launch time you set.',
  SCHEDULED: 'Cleared and waiting for its launch time.',
  REJECTED: 'This campaign was refused and cannot be submitted again.',
  LIVE: 'This campaign is live and taking pledges.',
};

interface Refusal {
  message: string;
  /** Named by the server. Replaces what this screen was showing; see the note above. */
  unmet: readonly UnmetRequirement[];
}

export interface ReviewPanelProps {
  projectId: string;
}

export function ReviewPanel({ projectId }: ReviewPanelProps) {
  /*
   * Two reads on open, deliberately. The shell needs the campaign's title and the
   * review needs the checklist, and they are different resources — folding the
   * checklist into `ProjectEdit` would put a query on `project_state_transitions`
   * behind every autosave in every other tab.
   */
  const { project, status, error, reload, apply } = useProjectEdit(projectId);

  const [checklist, setChecklist] = useState<ProjectChecklist | null>(null);
  const [checklistError, setChecklistError] = useState<string | null>(null);
  const [attempt, setAttempt] = useState(0);
  const [submitting, setSubmitting] = useState(false);
  const [refusal, setRefusal] = useState<Refusal | null>(null);

  const blockingHeading = useRef<HTMLHeadingElement>(null);

  useEffect(() => {
    const controller = new AbortController();

    void (async () => {
      try {
        const loaded = await getProjectChecklist(projectId, controller.signal);
        if (controller.signal.aborted) return;
        setChecklist(loaded);
        setChecklistError(null);
      } catch (cause) {
        if (controller.signal.aborted) return;
        setChecklistError(messageFor(cause));
      }
    })();

    return () => controller.abort();
  }, [projectId, attempt]);

  const reloadAll = useCallback(() => {
    setRefusal(null);
    setAttempt((n) => n + 1);
    reload();
  }, [reload]);

  async function submit(): Promise<void> {
    setSubmitting(true);
    setRefusal(null);
    try {
      apply(await submitProject(projectId));
      // The checklist is re-read rather than assumed: the state has changed, and
      // with it the moderation outcome's `current` flag.
      setAttempt((n) => n + 1);
    } catch (cause) {
      setRefusal(refusalFrom(cause));
    } finally {
      setSubmitting(false);
    }
  }

  if (status === 'signed-out') {
    return (
      <EditorShell projectId={projectId} active="review">
        <InlineAlert variant="info" title="You are signed out">
          This browser no longer has a session. Sign in again to keep editing this campaign.
        </InlineAlert>
      </EditorShell>
    );
  }

  if (status === 'failed' || checklistError !== null) {
    return (
      <EditorShell projectId={projectId} active="review">
        <InlineAlert variant="danger" title="This campaign could not be loaded">
          {error ?? checklistError}
        </InlineAlert>
        <Pill variant="ghost" size="sm" className="mt-4" onClick={reloadAll}>
          Try again
        </Pill>
      </EditorShell>
    );
  }

  if (project === null || checklist === null) {
    return (
      <EditorShell projectId={projectId} active="review">
        <SkeletonGroup label="Checking how complete this campaign is">
          <div className="flex flex-col gap-4">
            {LOADING_ROWS.map((row) => (
              <Skeleton key={row} height="2.5rem" />
            ))}
          </div>
        </SkeletonGroup>
      </EditorShell>
    );
  }

  const progress = progressOf(checklist);
  const blockers = unmetOf(checklist.blocking);
  const suggestions = unmetOf(checklist.advisory);
  const moderation = checklist.moderation;
  const canOfferSubmit = SUBMITTABLE_FROM.includes(checklist.state);
  const held = blockers.length > 0;

  return (
    <EditorShell
      projectId={projectId}
      active="review"
      title={project.title}
      state={checklist.state}
    >
      <div className="flex flex-col gap-8">
        {/*
          The moderator's note, first and prominent. §6.1 added CHANGES_REQUESTED so
          a fixable campaign could be sent back instead of rejected, and a creator
          told "changes requested" with no reason is the exact failure that state
          exists to prevent. `danger` for a refusal that is terminal, `warning` for
          one that is an invitation to fix something.
        */}
        {moderation != null && moderation.current && moderation.outcome !== 'APPROVED' && (
          <InlineAlert
            variant={moderation.outcome === 'REJECTED' ? 'danger' : 'warning'}
            title={
              moderation.outcome === 'REJECTED'
                ? 'This campaign was refused'
                : 'Our moderators have asked for changes'
            }
          >
            <p className="whitespace-pre-line text-white">
              {moderation.note ?? 'No reason was recorded.'}
            </p>
            <p className="mt-2">
              <time dateTime={moderation.decidedAt}>{formatDate(moderation.decidedAt)}</time>
            </p>
          </InlineAlert>
        )}

        {/*
          Waiting for review is a real state a creator sits in for days, and it must
          not look like something went wrong: `info`, not `warning`, and a sentence
          that says there is nothing to do.
        */}
        {STATE_NOTE[checklist.state] !== undefined && (
          <InlineAlert variant="info" title={PROJECT_STATE_LABEL[checklist.state]}>
            {STATE_NOTE[checklist.state]}
          </InlineAlert>
        )}

        {refusal !== null && (
          <InlineAlert variant="danger" title="This campaign was not submitted">
            <p>{refusal.message}</p>
            {refusal.unmet.length > 0 && (
              <ul className="mt-2 flex flex-col gap-1">
                {refusal.unmet.map((item) => (
                  <li key={item.requirement}>
                    {item.label}: {item.detail}
                  </li>
                ))}
              </ul>
            )}
            <Pill variant="ghost" size="sm" className="mt-3" onClick={reloadAll}>
              Check again
            </Pill>
          </InlineAlert>
        )}

        <section aria-labelledby="review-progress-heading">
          <h2
            id="review-progress-heading"
            className="text-[13px] font-medium tracking-[0.06em] text-white/40 uppercase"
          >
            Completeness
          </h2>
          {/*
            The sentence carries the meaning; the bar is a picture of it. Announcing
            both would say the same number twice, and the counts are the half that
            answers "is what is left optional".
          */}
          <p className="mt-2 text-[15px] text-white">{describeProgress(progress)}</p>
          <ProgressBar
            aria-hidden="true"
            value={progress.score}
            size="md"
            className="mt-3"
            label={`Campaign completeness: ${progress.score} percent`}
          />
        </section>

        <ChecklistSection
          id="review-required"
          headingRef={blockingHeading}
          heading="Required before you can submit"
          description="Every one of these comes from the platform's submission rules. A campaign cannot go to moderation until they are all done."
          items={checklist.blocking}
          projectId={projectId}
          tone="blocking"
        />

        <ChecklistSection
          id="review-recommended"
          heading="Recommended, but not required"
          description="None of these stops you submitting. Each one is something campaigns that do well tend to have."
          items={checklist.advisory}
          projectId={projectId}
          tone="advisory"
        />

        {canOfferSubmit && (
          <section aria-labelledby="review-submit-heading" className="flex flex-col gap-3">
            <h2 id="review-submit-heading" className="sr-only">
              Submit for review
            </h2>

            {/*
              `aria-disabled` rather than `disabled`, the same choice the section
              navigation makes: the control keeps its place in the tab order, so a
              keyboard user learns it exists and why it will not work, instead of
              never reaching it. Pressing it moves focus to the list of what is
              missing, which is the thing they were trying to find out.
            */}
            <Pill
              variant="accent"
              size="lg"
              aria-disabled={held || submitting}
              aria-describedby="review-submit-explanation"
              onClick={() => {
                if (submitting) return;
                if (held) {
                  blockingHeading.current?.focus();
                  return;
                }
                void submit();
              }}
              className={held || submitting ? 'opacity-40' : undefined}
            >
              {submitting ? 'Submitting' : 'Submit for review'}
            </Pill>

            <p id="review-submit-explanation" className="text-[13px] text-white/64">
              {held
                ? `${blockers.length} required ${
                    blockers.length === 1 ? 'item is' : 'items are'
                  } not done yet. ${
                    suggestions.length > 0
                      ? 'The recommended items are not part of this.'
                      : 'Finish them and this becomes available.'
                  }`
                : 'A moderator reads the campaign and either clears it, asks for changes, or refuses it. You can keep editing while it is in review.'}
            </p>
          </section>
        )}
      </div>
    </EditorShell>
  );
}

/* -------------------------------------------------------------------------
 * One group of requirements
 * ---------------------------------------------------------------------- */

interface ChecklistSectionProps {
  id: string;
  heading: string;
  description: string;
  items: readonly ChecklistItem[];
  projectId: string;
  tone: 'blocking' | 'advisory';
  headingRef?: React.Ref<HTMLHeadingElement>;
}

function ChecklistSection({
  id,
  heading,
  description,
  items,
  projectId,
  tone,
  headingRef,
}: ChecklistSectionProps) {
  if (items.length === 0) return null;

  return (
    <section aria-labelledby={`${id}-heading`}>
      {/*
        `tabIndex={-1}` so the submit control can move focus here. It is not in the
        tab order; it is a target, which is what -1 means.
      */}
      <h2
        id={`${id}-heading`}
        ref={headingRef}
        tabIndex={-1}
        className="text-[15px] font-medium text-white focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-white"
      >
        {heading}
      </h2>
      <p className="mt-1 text-[13px] text-white/64">{description}</p>

      <ul className="mt-4 flex flex-col gap-2">
        {items.map((item) => (
          <ChecklistRow key={item.requirement} item={item} projectId={projectId} tone={tone} />
        ))}
      </ul>
    </section>
  );
}

function ChecklistRow({
  item,
  projectId,
  tone,
}: {
  item: ChecklistItem;
  projectId: string;
  tone: 'blocking' | 'advisory';
}) {
  const href = item.satisfied ? null : sectionHref(projectId, item.section);

  /*
    Three shapes, three words. The icon distinguishes the rows for somebody who
    cannot tell the colours apart, and the spoken status distinguishes them for
    somebody who cannot see either — a checklist that relied on green and red
    would be unreadable to both (docs/ui-kit.md §9.2).
  */
  const status = item.satisfied
    ? 'Done'
    : tone === 'blocking'
      ? 'Required, not done'
      : 'Recommended, not done';

  return (
    <li className="flex items-start gap-3 rounded-lg border border-white/8 bg-surface-2 p-3">
      {item.satisfied ? (
        <CircleCheck aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-success" />
      ) : tone === 'blocking' ? (
        <CircleAlert aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-danger" />
      ) : (
        <Info aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-info" />
      )}

      <div className="min-w-0 flex-1">
        <p className="text-[15px] text-white">
          {item.label}
          <span className="sr-only">, {status}</span>
        </p>
        {!item.satisfied && item.detail != null && (
          <p className="mt-1 text-[13px] text-white/64">{item.detail}</p>
        )}
      </div>

      {href !== null && isChecklistSection(item.section) && (
        /*
          The link is what makes the row actionable. Its accessible name names the
          requirement as well as the section, because "Fix in Basics" repeated four
          times is four indistinguishable links in a screen reader's list.
        */
        <Link
          href={href}
          className="shrink-0 rounded-full bg-surface-3 px-3 py-1 text-[13px] text-white transition-colors duration-150 ease-in-out hover:bg-surface-4"
        >
          Fix in {SECTION_LABEL[item.section]}
          <span className="sr-only">: {item.label}</span>
        </Link>
      )}
    </li>
  );
}

/* -------------------------------------------------------------------------
 * Failures
 * ---------------------------------------------------------------------- */

function messageFor(cause: unknown): string {
  if (cause instanceof ApiError) {
    if (cause.status === 404) return 'That campaign could not be found.';
    if (cause.status === 403) return 'You do not have access to this campaign.';
    return cause.problem?.detail ?? cause.problem?.title ?? 'The service refused the request.';
  }
  return 'The service could not be reached. Check your connection and try again.';
}

/**
 * A refused submission, as something to render.
 *
 * `PROJECT_NOT_SUBMITTABLE` carries the requirements. `PROJECT_TRANSITION_NOT_ALLOWED`
 * carries none — it means the campaign is not in a state it can be submitted from,
 * which is usually another tab or a moderator having moved it, so the message is
 * the server's and the fix is to reload.
 */
function refusalFrom(cause: unknown): Refusal {
  if (cause instanceof ApiError) {
    return {
      message: messageFor(cause),
      unmet: unmetFromRefusal(cause.problem?.meta),
    };
  }
  return { message: messageFor(cause), unmet: [] };
}

function formatDate(iso: string): string {
  const when = new Date(iso);
  if (Number.isNaN(when.getTime())) return iso;
  return when.toLocaleDateString('en-GB', { day: 'numeric', month: 'long', year: 'numeric' });
}
