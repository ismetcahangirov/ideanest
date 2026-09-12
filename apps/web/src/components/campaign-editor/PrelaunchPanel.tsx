'use client';

import { useEffect, useState } from 'react';
import { Check, Copy, Users } from 'lucide-react';
import {
  CharacterCount,
  Field,
  InlineAlert,
  Pill,
  Skeleton,
  SkeletonGroup,
  Textarea,
  TextInput,
} from '@ideanest/ui';
import { Modal } from '@ideanest/ui/motion';
import {
  getPrelaunchPage,
  openPrelaunch,
  patchProject,
  type CoverImage,
  type ProjectEdit,
  type ProjectPatch,
  type ProjectState,
} from '../../lib/projects/api';
import {
  BLURB_MAX_CHARACTERS,
  TITLE_MAX_CHARACTERS,
  characterCount,
  draftFromProject,
  isBasicsField,
  patchForField,
  validateBasics,
  type BasicsDraft,
  type BasicsErrors,
  type BasicsField,
} from '../../lib/projects/basics';
import { CoverImageField } from './CoverImageField';
import type { PrelaunchCopy } from '../../lib/i18n/editor-copy';
import { fillNodes, fillPlaceholders } from '../../lib/i18n/placeholders';
import { pluralForm } from '../../lib/i18n/plurals';
import { useRouteLocale } from '../../lib/i18n/useRouteLocale';
import { EditorShell } from './EditorShell';
import { SaveStatus } from './SaveStatus';
import { useAutosave, describeFailure, type SaveFailure } from './useAutosave';
import { useProjectEdit } from './useProjectEdit';

/**
 * The pre-launch tab: what the pre-launch page will say, the link to share, how
 * many people are waiting, and the control that makes it public.
 *
 * THE CONTENT IS THE BASICS, ON PURPOSE. There is no separate pre-launch
 * headline or pre-launch summary. A dedicated pair would let a creator promise
 * one thing on the page people follow and a different thing on the campaign it
 * becomes, and the follower signed up for the first. So this edits `title`,
 * `blurb`, and `coverImage` through the same `PATCH /v1/projects/{id}` autosave
 * the Basics tab uses — the same fields, shown here in the arrangement the
 * public page renders them in, so a creator can see what they are publishing
 * rather than imagining it.
 *
 * OPENING THE PAGE IS NOT AN AUTOSAVE. It publishes the campaign's title,
 * summary, and cover to anybody with the link, and docs/architecture.md §6.1 has
 * no edge back — there is no PRELAUNCH → DRAFT. A switch that did that silently
 * on the way past would be the worst control on the platform, so it is a button,
 * behind a dialog that says what cannot be undone.
 *
 * MOTION: none beyond the save indicator (docs/motion-system.md §5, "campaign
 * editor — none"). The modal's own entry is the overlay pattern of §4.11 and
 * belongs to the component, not to this surface's budget.
 */

const LOADING_ROWS = [0, 1, 2];

/** The states in which a pre-launch page exists and collects followers. */
const COLLECTING: readonly ProjectState[] = ['PRELAUNCH', 'SCHEDULED'];

/** Maps a validation failure's `errors` map onto the fields this form has. */
function serverErrors(failure: SaveFailure | null): BasicsErrors {
  if (failure === null) return {};

  const mapped: BasicsErrors = {};
  for (const [key, message] of Object.entries(failure.fieldErrors)) {
    const [field = ''] = key.split('.');
    if (isBasicsField(field)) mapped[field] = message;
  }
  return mapped;
}

/**
 * The address to share.
 *
 * Built from `window.location.origin` rather than from a configured base URL,
 * because the correct host is whichever one the creator is looking at — a
 * staging deployment that handed out production links would be a link that
 * showed the wrong campaign. Empty on the server, where there is no origin; the
 * field is read-only and shows nothing until the client has hydrated, which is
 * one frame.
 */
function prelaunchLink(projectId: string): string {
  if (typeof window === 'undefined') return '';
  return `${window.location.origin}/projects/${encodeURIComponent(projectId)}/prelaunch`;
}

export interface PrelaunchPanelProps {
  projectId: string;
  /**
   * Every word this tab draws, resolved on the server — issue #459.
   *
   * This panel is a client component and has to be: the form autosaves as it is typed. A
   * `useTranslations` here would need a `NextIntlClientProvider` above it, which this
   * repository measured at up to 27.4 KiB on every route in a group; the page reads the
   * catalogue instead and hands the words down. `lib/i18n/editor-copy.ts` carries the
   * argument.
   */
  copy: PrelaunchCopy;
}

export function PrelaunchPanel({ projectId, copy }: PrelaunchPanelProps) {
  const { project, status, error, reload, apply } = useProjectEdit(projectId, copy.frame.failures.load);

  /* The language, for the one sentence here that declines: how many people are waiting. */
  const locale = useRouteLocale();

  /** Seeded once, for the reason `BasicsPanel` gives: re-seeding eats keystrokes. */
  const [draft, setDraft] = useState<BasicsDraft | null>(null);

  const [confirming, setConfirming] = useState(false);
  const [opening, setOpening] = useState(false);
  const [openFailure, setOpenFailure] = useState<SaveFailure | null>(null);
  const [copied, setCopied] = useState(false);
  const [followerCount, setFollowerCount] = useState<number | null>(null);

  const autosave = useAutosave<ProjectPatch, ProjectEdit>({
    send: (patch) => patchProject(projectId, patch),
    onSaved: apply,
    failures: copy.frame.failures.save,
  });

  useEffect(() => {
    if (project !== null && draft === null) setDraft(draftFromProject(project));
  }, [project, draft]);

  const collecting = project !== null && COLLECTING.includes(project.state);

  useEffect(() => {
    if (!collecting) return;

    const controller = new AbortController();
    void (async () => {
      try {
        const page = await getPrelaunchPage(projectId, controller.signal);
        setFollowerCount(page.followerCount);
      } catch {
        /*
         * The count is the one thing on this tab that is read from the public
         * endpoint, and losing it must not take the editor with it. `null` is
         * rendered as "not available" rather than as zero — telling a creator
         * that nobody has signed up when the request simply failed is worse than
         * telling them nothing.
         */
        if (!controller.signal.aborted) setFollowerCount(null);
      }
    })();

    return () => controller.abort();
  }, [projectId, collecting]);

  function change(field: BasicsField, next: BasicsDraft): void {
    setDraft(next);

    const patch = patchForField(field, next);
    if (patch !== null) autosave.save(patch);
  }

  async function open(): Promise<void> {
    setOpening(true);
    setOpenFailure(null);
    try {
      // Anything still queued goes first. Opening the page publishes whatever the
      // server holds, and a summary typed a second ago is not there yet.
      autosave.flush();
      apply(await openPrelaunch(projectId));
      setConfirming(false);
    } catch (cause) {
      setOpenFailure(describeFailure(cause, copy.frame.failures.save));
    } finally {
      setOpening(false);
    }
  }

  async function copyLink(): Promise<void> {
    try {
      await navigator.clipboard.writeText(prelaunchLink(projectId));
      setCopied(true);
      window.setTimeout(() => setCopied(false), 2000);
    } catch {
      // Clipboard access can be refused, and there is nothing to do about it:
      // the address is in a focusable read-only field beside the button, so the
      // fallback is the ordinary one of selecting it.
      setCopied(false);
    }
  }

  if (status === 'signed-out') {
    return (
      <EditorShell projectId={projectId} copy={copy.frame} active="prelaunch">
        <InlineAlert variant="info" title={copy.frame.signedOut.title}>
          {copy.frame.signedOut.body}
        </InlineAlert>
      </EditorShell>
    );
  }

  if (status === 'failed' || draft === null || project === null) {
    return (
      <EditorShell projectId={projectId} copy={copy.frame} active="prelaunch">
        {status === 'failed' ? (
          <>
            <InlineAlert variant="danger" title={copy.frame.loadFailed}>
              {error}
            </InlineAlert>
            <Pill variant="ghost" size="sm" className="mt-4" onClick={reload}>
              {copy.frame.tryAgain}
            </Pill>
          </>
        ) : (
          <SkeletonGroup label={copy.loading}>
            <div className="flex flex-col gap-6">
              {LOADING_ROWS.map((row) => (
                <div key={row} className="flex flex-col gap-2">
                  <Skeleton height="0.875rem" width="30%" />
                  <Skeleton height="2.75rem" />
                </div>
              ))}
            </div>
          </SkeletonGroup>
        )}
      </EditorShell>
    );
  }

  const errors: BasicsErrors = {
    ...validateBasics(draft, copy.errors),
    ...serverErrors(autosave.failure),
  };
  const canOpen = project.state === 'DRAFT';
  const closed = !canOpen && !collecting;

  return (
    <EditorShell
      projectId={projectId}
      copy={copy.frame}
      active="prelaunch"
      title={project.title}
      state={project.state}
      status={<SaveStatus state={autosave.state} copy={copy.frame.save} />}
    >
      <div className="flex flex-col gap-7">
        {autosave.failure !== null && (
          <InlineAlert variant="danger" title={copy.saveFailed.title}>
            <p>{autosave.failure.message}</p>
            <p className="mt-2 text-white/64">{copy.saveFailed.kept}</p>
            <Pill variant="ghost" size="sm" className="mt-3" onClick={autosave.retry}>
              {copy.frame.tryAgain}
            </Pill>
          </InlineAlert>
        )}

        {/* ---------------------------------------------------------------
            The state of the page itself: not opened, open, or finished.
           --------------------------------------------------------------- */}

        {canOpen && (
          <section
            aria-labelledby="prelaunch-open-heading"
            className="rounded-lg border border-white/8 bg-surface-2 p-5"
          >
            <h2 id="prelaunch-open-heading" className="text-base font-semibold text-white">
              {copy.notOpen.heading}
            </h2>
            <p className="mt-2 text-[13px] text-white/64">{copy.notOpen.body}</p>
            <p className="mt-2 text-[13px] text-white/64">{copy.notOpen.permanent}</p>
            {openFailure !== null && (
              <InlineAlert variant="danger" title={copy.notOpen.failed} className="mt-4">
                {openFailure.message}
              </InlineAlert>
            )}
            <Pill className="mt-4" onClick={() => setConfirming(true)}>
              {copy.notOpen.action}
            </Pill>
          </section>
        )}

        {collecting && (
          <section
            aria-labelledby="prelaunch-live-heading"
            className="rounded-lg border border-white/8 bg-surface-2 p-5"
          >
            <h2 id="prelaunch-live-heading" className="text-base font-semibold text-white">
              {copy.live.heading}
            </h2>

            <div className="mt-4 flex items-center gap-2 text-sm text-white">
              {/* An icon and a word. Colour alone never carries meaning
                  (docs/ui-kit.md §9.2), and a bare number beside a glyph is not a
                  sentence a screen reader can read out usefully. */}
              <Users aria-hidden="true" className="size-4 text-white/64" />
              {followerCount === null ? (
                <span className="text-white/64">{copy.live.countUnavailable}</span>
              ) : (
                /*
                  ONE SENTENCE WITH THE NUMBER IN IT, not a bold number and an English clause
                  after it. The count decides the form of the verb, and in Russian it decides
                  the form of the noun as well — `fillNodes` puts the styled number wherever the
                  translator's own word order puts it (#459).
                */
                <span>
                  {fillNodes(pluralForm(locale, copy.live.waiting, followerCount), {
                    count: <strong className="font-semibold">{followerCount}</strong>,
                  })}
                </span>
              )}
            </div>

            <Field
              label={copy.live.linkLabel}
              hint={copy.live.linkHint}
              className="mt-5"
            >
              <div className="flex gap-2">
                {/* Read-only rather than disabled: a disabled input cannot be
                    focused, selected, or read by a screen reader on its own, and
                    selecting the address by hand is the fallback when the
                    clipboard is refused. */}
                <TextInput readOnly value={prelaunchLink(projectId)} className="font-mono" />
                <Pill
                  variant="ghost"
                  onClick={copyLink}
                  iconLeft={
                    copied ? (
                      <Check aria-hidden="true" className="size-4" />
                    ) : (
                      <Copy aria-hidden="true" className="size-4" />
                    )
                  }
                >
                  {copied ? copy.live.copied : copy.live.copy}
                </Pill>
              </div>
              {/* Announced rather than only shown, so that a keyboard user who
                  pressed Copy is told it worked. Present from the first render so
                  the region is registered before anything is put in it. */}
              <span role="status" aria-live="polite" className="sr-only">
                {copied ? copy.live.copiedAnnounced : ''}
              </span>
            </Field>
          </section>
        )}

        {closed && (
          <InlineAlert variant="info" title={copy.closed.title}>
            {copy.closed.body}
          </InlineAlert>
        )}

        {/* ---------------------------------------------------------------
            What the page says. The Basics fields, in the order the public
            page renders them.
           --------------------------------------------------------------- */}

        <form className="flex flex-col gap-7" onSubmit={(event) => event.preventDefault()}>
          <div>
            <h2 className="text-base font-semibold text-white">{copy.form.heading}</h2>
            <p className="mt-1 text-[13px] text-white/64">{copy.form.intro}</p>
          </div>

          <Field
            label={copy.form.titleLabel}
            required
            hint={fillPlaceholders(copy.form.titleHint, { max: String(TITLE_MAX_CHARACTERS) })}
            error={errors.title}
          >
            <TextInput
              value={draft.title}
              autoComplete="off"
              onChange={(event) => change('title', { ...draft, title: event.target.value })}
              onBlur={autosave.flush}
            />
            <CharacterCount count={characterCount(draft.title)} limit={TITLE_MAX_CHARACTERS} />
          </Field>

          <Field
            label={copy.form.blurbLabel}
            hint={fillPlaceholders(copy.form.blurbHint, { max: String(BLURB_MAX_CHARACTERS) })}
            error={errors.blurb}
          >
            <Textarea
              rows={3}
              value={draft.blurb}
              onChange={(event) => change('blurb', { ...draft, blurb: event.target.value })}
              onBlur={autosave.flush}
            />
            <CharacterCount count={characterCount(draft.blurb)} limit={BLURB_MAX_CHARACTERS} />
          </Field>

          <CoverImageField
            copy={copy.cover}
            url={draft.coverImageUrl}
            cover={draft.coverImage}
            error={errors.coverImage}
            onUrlChange={(url) => setDraft({ ...draft, coverImageUrl: url })}
            onAccept={(cover: CoverImage) =>
              change('coverImage', { ...draft, coverImage: cover, coverImageUrl: cover.url })
            }
            onRemove={() => change('coverImage', { ...draft, coverImage: null, coverImageUrl: '' })}
          />
        </form>
      </div>

      <Modal
        open={confirming}
        onOpenChange={setConfirming}
        size="sm"
        title={copy.confirm.title}
        description={copy.confirm.intro}
        footer={
          <div className="flex justify-end gap-2">
            <Pill variant="outline" onClick={() => setConfirming(false)} disabled={opening}>
              {copy.confirm.cancel}
            </Pill>
            <Pill onClick={() => void open()} disabled={opening}>
              {opening ? copy.confirm.opening : copy.confirm.action}
            </Pill>
          </div>
        }
      >
        {/* `text-on-white`, not `text-white`: the modal is the one white surface
            in the system (docs/ui-kit.md §7.14), and white text on it is
            invisible. */}
        <p className="text-sm text-on-white/64">{copy.confirm.body}</p>
      </Modal>
    </EditorShell>
  );
}
