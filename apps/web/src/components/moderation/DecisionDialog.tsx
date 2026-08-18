'use client';

import { useEffect, useState } from 'react';
import { CharacterCount, Field, InlineAlert, Pill, Textarea } from '@ideanest/ui';
import { Modal } from '@ideanest/ui/motion';
import type { CampaignOutcome, QueuedReport, ReportOutcome } from '../../lib/moderation/api';
import { requiresNote } from '../../lib/moderation/api';
import { shortId, targetLabel } from '../../lib/moderation/describe';

/**
 * Which of the five verbs is being confirmed.
 *
 * A discriminated union rather than a flat string, because the two halves reach
 * different endpoints with different consequences and the type is what stops one
 * being sent to the other's function.
 */
export type Decision =
  | { readonly kind: 'report'; readonly outcome: ReportOutcome }
  | { readonly kind: 'campaign'; readonly outcome: CampaignOutcome };

/** What the note may hold — the `@Size` both request bodies carry. */
export const NOTE_MAX_CHARACTERS = 2000;

interface Copy {
  readonly title: string;
  readonly description: string;
  readonly body: string;
  readonly confirmLabel: string;
  readonly busyLabel: string;
  readonly noteLabel: string;
  readonly noteHint: string;
  readonly noteRequired: boolean;
  readonly destructive: boolean;
}

/**
 * The words for each verb, in one table.
 *
 * Every `description` says what the decision does AND what it does not do. The
 * service is emphatic that upholding a report does not suspend anything and that
 * rejecting a campaign does not close the complaint; a moderator who believes
 * one button did both has been misled by the interface into thinking a job is
 * finished.
 */
function copyFor(decision: Decision, report: QueuedReport): Copy {
  const kind = targetLabel(report.target.type);
  const name = `${kind} ${shortId(report.target.id)}`;

  if (decision.kind === 'report') {
    return decision.outcome === 'uphold'
      ? {
          title: 'Uphold this report?',
          description: `The complaint about ${name} is recorded as justified. This cannot be undone.`,
          body: 'Nothing is taken down by this. Suspending a campaign or banning an account are separate decisions, and this one does not make them for you.',
          confirmLabel: 'Uphold report',
          busyLabel: 'Upholding',
          noteLabel: 'Note',
          noteHint:
            'Optional. Written for the next moderator, not for the person who reported this — nothing shows it to them.',
          noteRequired: false,
          destructive: false,
        }
      : {
          title: 'Dismiss this report?',
          description: `The complaint about ${name} is recorded as unjustified. This cannot be undone.`,
          body: 'Dismissals are audited too. "Who dismissed the fourteen reports about this campaign" is the question an investigation starts from.',
          confirmLabel: 'Dismiss report',
          busyLabel: 'Dismissing',
          noteLabel: 'Note',
          noteHint:
            'Optional. Written for the next moderator, not for the person who reported this — nothing shows it to them.',
          noteRequired: false,
          destructive: false,
        };
  }

  const shared = {
    body: 'This moves the campaign. It does not decide the report — that is still open, and still yours to uphold or dismiss.',
    noteRequired: requiresNote(decision.outcome),
  } as const;

  switch (decision.outcome) {
    case 'approve':
      return {
        ...shared,
        title: 'Approve this campaign?',
        description: `${name} is cleared for launch.`,
        confirmLabel: 'Approve campaign',
        busyLabel: 'Approving',
        noteLabel: 'Note',
        noteHint: 'Optional commentary on the decision.',
        destructive: false,
      };
    case 'request-changes':
      return {
        ...shared,
        title: 'Send this campaign back?',
        description: `${name} goes back to its creator, who can fix it and submit again.`,
        confirmLabel: 'Request changes',
        busyLabel: 'Sending back',
        noteLabel: 'What has to change',
        noteHint:
          'Required. The creator is shown this and has to act on it — it is the entire content of the state.',
        destructive: false,
      };
    case 'reject':
      return {
        ...shared,
        title: 'Reject this campaign?',
        description: `${name} is refused. Rejection is TERMINAL — there is no way back to a draft.`,
        confirmLabel: 'Reject campaign',
        busyLabel: 'Rejecting',
        noteLabel: 'Why',
        noteHint:
          'Required. The creator is shown this. If the problem is fixable, send the campaign back instead.',
        destructive: true,
      };
  }
}

export interface DecisionDialogProps {
  readonly decision: Decision | null;
  readonly report: QueuedReport | null;
  readonly busy: boolean;
  /** A refusal from the service. Keeps the dialog open — nothing has changed. */
  readonly error: string | null;
  readonly onCancel: () => void;
  readonly onConfirm: (note: string | null) => void;
}

/**
 * The one place a moderation decision is committed.
 *
 * A dialog rather than an inline form because every one of these five verbs is
 * privileged and hard to reverse, and because `Modal` is the only overlay that
 * traps focus and gives it back to the control that opened it
 * (docs/ui-kit.md §7.14). Backdrop dismissal is off — the user has to make a
 * choice — and Escape stays on, because cancelling IS one of the two choices.
 *
 * MOTION: the modal's own 200ms entry (docs/motion-system.md §4.11.1) and
 * nothing else. That is the overlay budget even on checkout, and it collapses to
 * an instant state change under `prefers-reduced-motion`.
 */
export function DecisionDialog({
  decision,
  report,
  busy,
  error,
  onCancel,
  onConfirm,
}: DecisionDialogProps) {
  const [note, setNote] = useState('');
  const [noteError, setNoteError] = useState<string | null>(null);

  const key = decision === null || report === null ? null : `${report.id}:${decision.outcome}`;

  /*
   * A note typed for one decision must not survive into the next one. Keyed on
   * the pair rather than on `open`, so reopening the same dialog after a refusal
   * keeps what was written — the moderator has not changed their mind, the
   * service refused.
   */
  useEffect(() => {
    setNote('');
    setNoteError(null);
  }, [key]);

  if (decision === null || report === null) {
    // Nothing to confirm. Rendering a closed `Modal` would put a dialog with no
    // title in the tree, and `title` is what names it.
    return null;
  }

  const copy = copyFor(decision, report);
  const count = Array.from(note).length;

  function confirm(): void {
    const trimmed = note.trim();

    if (copy.noteRequired && trimmed === '') {
      setNoteError('Say why. The creator is shown this note and has to act on it.');
      return;
    }
    if (count > NOTE_MAX_CHARACTERS) {
      setNoteError(`A note may not exceed ${NOTE_MAX_CHARACTERS} characters.`);
      return;
    }

    setNoteError(null);
    // An empty optional note is absent, not an empty string: a blank note that
    // round-trips reads as a moderator who wrote nothing on purpose.
    onConfirm(trimmed === '' ? null : trimmed);
  }

  return (
    <Modal
      open
      onOpenChange={(next) => {
        if (!next && !busy) onCancel();
      }}
      size="md"
      title={copy.title}
      description={copy.description}
      closeOnBackdropClick={false}
      showClose={false}
      footer={
        <>
          <Pill variant="ghost" disabled={busy} onClick={onCancel}>
            Cancel
          </Pill>
          <Pill
            variant={copy.destructive ? 'danger' : 'primary'}
            disabled={busy}
            onClick={confirm}
          >
            {busy ? copy.busyLabel : copy.confirmLabel}
          </Pill>
        </>
      }
    >
      <p className="text-sm text-on-white/72">{copy.body}</p>

      <div className="mt-4">
        <Field
          label={copy.noteLabel}
          required={copy.noteRequired}
          hint={copy.noteHint}
          error={noteError}
        >
          {/*
            No `maxLength`. A hard cap silently truncates a pasted note and takes
            the counter's only useful message away — "3 characters too many" is
            actionable, losing three words is not.
          */}
          <Textarea
            rows={4}
            value={note}
            disabled={busy}
            onChange={(event) => setNote(event.target.value)}
          />
          <CharacterCount count={count} limit={NOTE_MAX_CHARACTERS} />
        </Field>
      </div>

      {/* `InlineAlert` carries `role="alert"` for danger — a refused privileged
          action must interrupt rather than wait to be noticed. */}
      {error && (
        <InlineAlert variant="danger" title="The decision was not recorded" className="mt-4">
          {error}
        </InlineAlert>
      )}
    </Modal>
  );
}
