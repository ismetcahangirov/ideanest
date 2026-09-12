'use client';

import { useEffect, useId, useState } from 'react';
import {
  CharacterCount,
  Field,
  InlineAlert,
  Pill,
  Select,
  Skeleton,
  SkeletonGroup,
  Switch,
  Textarea,
  TextInput,
} from '@ideanest/ui';
import { SUPPORTED_CURRENCIES } from '../../lib/money';
import { fillPlaceholders } from '../../lib/i18n/placeholders';
import {
  isLocked,
  listCategories,
  patchProject,
  type Category,
  type CoverImage,
  type ProjectEdit,
  type ProjectPatch,
} from '../../lib/projects/api';
import {
  BLURB_MAX_CHARACTERS,
  DURATION_MAX_DAYS,
  DURATION_MIN_DAYS,
  DURATION_RECOMMENDED_DAYS,
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
import type { BasicsCopy } from '../../lib/i18n/editor-copy';
import { EditorShell } from './EditorShell';
import { SaveStatus } from './SaveStatus';
import { useAutosave, type SaveFailure } from './useAutosave';
import { useProjectEdit } from './useProjectEdit';

/**
 * The basics tab: title, summary, category, goal, duration, scheduled launch,
 * late pledges, and the cover image.
 *
 * There is no save button. Every control writes its own field through
 * `PATCH /v1/projects/{id}` on a debounce (`useAutosave`), and the indicator in
 * the header is the only thing that reports on it — which is why that indicator
 * never says "Saved" while anything is still queued.
 *
 * MOTION: none, beyond the save indicator (docs/motion-system.md §5). Creators
 * spend hours in here and a field that animates while somebody is typing into it
 * reads as hesitation.
 *
 * TWO THINGS ARE DELIBERATELY ABSENT.
 *
 * Location. `projects` has no `location_id` and there is no geocoding service;
 * docs/architecture.md §7.2 and the epic contract both hold it back for the
 * discovery epic (#42). A location field with nowhere to save to would be a
 * form that forgets what it was told, so there is not one.
 *
 * Video. §4.6 lists it beside the cover image, and it needs the same media
 * pipeline the cover is waiting for — with the additional problem that a video
 * has no equivalent of reading intrinsic dimensions from an `<img>`.
 */

const LOADING_ROWS = [0, 1, 2, 3];

/** Maps a validation failure's `errors` map onto the fields this form has. */
function serverErrors(failure: SaveFailure | null): BasicsErrors {
  if (failure === null) return {};

  const mapped: BasicsErrors = {};
  for (const [key, message] of Object.entries(failure.fieldErrors)) {
    // `goal.amount` is about the goal field. Anything unrecognised is dropped
    // rather than rendered next to a control it is not about; the whole message
    // is still shown in the failure banner.
    const [field = ''] = key.split('.');
    if (isBasicsField(field)) mapped[field] = message;
  }
  return mapped;
}

export interface BasicsPanelProps {
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
  copy: BasicsCopy;
}

export function BasicsPanel({ projectId, copy }: BasicsPanelProps) {
  const { project, status, error, reload, apply } = useProjectEdit(projectId, copy.frame.failures.load);

  /**
   * The form's state, seeded once from the project.
   *
   * Once, and never again from a later response: the answer to a save arrives
   * while the creator is already typing the next sentence, and re-seeding from
   * it would delete those keystrokes. The server's copy is kept in `project` for
   * the things it is the authority on — state, `lockedFields` — and the fields
   * belong to the draft.
   */
  const [draft, setDraft] = useState<BasicsDraft | null>(null);

  const [categories, setCategories] = useState<readonly Category[] | null>(null);
  const [categoriesUnavailable, setCategoriesUnavailable] = useState(false);

  const latePledgeHintId = useId();

  const autosave = useAutosave<ProjectPatch, ProjectEdit>({
    send: (patch) => patchProject(projectId, patch),
    onSaved: apply,
    failures: copy.frame.failures.save,
  });

  useEffect(() => {
    if (project !== null && draft === null) setDraft(draftFromProject(project));
  }, [project, draft]);

  useEffect(() => {
    const controller = new AbortController();

    void (async () => {
      try {
        setCategories(await listCategories(controller.signal));
      } catch {
        /*
         * The rest of the form still works without the taxonomy, and saying
         * that the list is unavailable is better than blocking the editor on a
         * read it does not need in order to save a title. The names come back
         * already resolved against the browser's `Accept-Language`, so there is
         * nothing to choose between here.
         */
        if (!controller.signal.aborted) setCategoriesUnavailable(true);
      }
    })();

    return () => controller.abort();
  }, []);

  /**
   * Applies one field and queues exactly that field.
   *
   * The patch is built from the field name, not from the whole draft, so an
   * untouched control can never overwrite what another tab wrote. When the value
   * is not valid nothing is sent at all — the field shows why, and the last
   * value the server accepted stays the value the server holds.
   */
  function change(field: BasicsField, next: BasicsDraft): void {
    setDraft(next);

    const patch = patchForField(field, next);
    if (patch !== null) autosave.save(patch);
  }

  const failure = autosave.failure;

  if (status === 'signed-out') {
    return (
      <EditorShell projectId={projectId} copy={copy.frame} active="basics">
        <InlineAlert variant="info" title={copy.frame.signedOut.title}>
          {copy.frame.signedOut.body}
        </InlineAlert>
      </EditorShell>
    );
  }

  if (status === 'failed' || draft === null || project === null) {
    return (
      <EditorShell projectId={projectId} copy={copy.frame} active="basics">
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

  const errors: BasicsErrors = { ...validateBasics(draft, copy.errors), ...serverErrors(failure) };
  const selected = categories?.find((category) => category.id === draft.categoryId) ?? null;
  const subcategories = selected?.subcategories ?? [];

  const goalLocked = isLocked(project, 'goal');
  const durationLocked = isLocked(project, 'durationDays');

  return (
    <EditorShell
      projectId={projectId}
      copy={copy.frame}
      active="basics"
      title={project.title}
      state={project.state}
      status={<SaveStatus state={autosave.state} copy={copy.frame.save} />}
    >
      {/*
        There is no submit. The element is a `form` so that Enter inside a field
        does not do something surprising, and so the whole group is announced as
        one region rather than as a run of unrelated controls.
      */}
      <form className="flex flex-col gap-7" onSubmit={(event) => event.preventDefault()}>
        {failure !== null && (
          <InlineAlert variant="danger" title={copy.saveFailed.title}>
            <p>{failure.message}</p>
            <p className="mt-2 text-white/64">{copy.saveFailed.kept}</p>
            <Pill variant="ghost" size="sm" className="mt-3" onClick={autosave.retry}>
              {copy.frame.tryAgain}
            </Pill>
          </InlineAlert>
        )}

        <Field
          label={copy.title.label}
          required
          hint={fillPlaceholders(copy.title.hint, { max: String(TITLE_MAX_CHARACTERS) })}
          error={errors.title}
        >
          {/*
            No `maxLength`. A hard cap truncates a pasted title without saying
            so, and it takes the counter's only useful message away: "3
            characters too many" is actionable, silently losing three letters is
            not.
          */}
          <TextInput
            value={draft.title}
            autoComplete="off"
            onChange={(event) => change('title', { ...draft, title: event.target.value })}
            onBlur={autosave.flush}
          />
          <CharacterCount count={characterCount(draft.title)} limit={TITLE_MAX_CHARACTERS} />
        </Field>

        <Field
          label={copy.blurb.label}
          hint={fillPlaceholders(copy.blurb.hint, { max: String(BLURB_MAX_CHARACTERS) })}
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

        {categoriesUnavailable && (
          <InlineAlert variant="warning" title={copy.categoriesUnavailable.title}>
            {copy.categoriesUnavailable.body}
          </InlineAlert>
        )}

        <div className="grid gap-6 sm:grid-cols-2">
          <Field
            label={copy.category.label}
            hint={copy.category.hint}
            error={errors.categoryId}
          >
            <Select
              value={draft.categoryId}
              placeholder={copy.category.placeholder}
              disabled={categories === null}
              onChange={(event) =>
                change('categoryId', {
                  ...draft,
                  categoryId: event.target.value,
                  // A subcategory of a category nobody chose is orphaned data.
                  subcategoryId: '',
                })
              }
            >
              {(categories ?? []).map((category) => (
                <option key={category.id} value={category.id}>
                  {category.name}
                </option>
              ))}
            </Select>
          </Field>

          <Field
            label={copy.subcategory.label}
            hint={
              selected === null
                ? copy.subcategory.chooseCategory
                : subcategories.length === 0
                  ? copy.subcategory.none
                  : copy.subcategory.optional
            }
            error={errors.subcategoryId}
          >
            <Select
              value={draft.subcategoryId}
              placeholder={copy.subcategory.placeholder}
              disabled={subcategories.length === 0}
              onChange={(event) =>
                change('subcategoryId', { ...draft, subcategoryId: event.target.value })
              }
            >
              {subcategories.map((subcategory) => (
                <option key={subcategory.id} value={subcategory.id}>
                  {subcategory.name}
                </option>
              ))}
            </Select>
          </Field>
        </div>

        <div className="grid gap-6 sm:grid-cols-[2fr_1fr]">
          <Field
            label={copy.goal.label}
            required
            hint={goalLocked ? copy.goal.locked : copy.goal.hint}
            error={errors.goal}
          >
            {/*
              `inputMode="decimal"` rather than `type="number"`. A number input
              accepts `1e5`, hides what it cannot parse, and on several browsers
              silently loses the value on a scroll wheel — none of which is
              acceptable for the figure the whole campaign is measured against.
              The value stays text here and becomes a `Decimal` on the way out.
            */}
            <TextInput
              inputMode="decimal"
              autoComplete="off"
              value={draft.goalAmount}
              disabled={goalLocked}
              onChange={(event) => change('goal', { ...draft, goalAmount: event.target.value })}
              onBlur={autosave.flush}
            />
          </Field>

          <Field label={copy.currency.label} hint={copy.currency.hint}>
            <Select
              value={draft.currency}
              disabled={goalLocked}
              onChange={(event) => change('goal', { ...draft, currency: event.target.value })}
            >
              {SUPPORTED_CURRENCIES.map((currency) => (
                <option key={currency} value={currency}>
                  {currency}
                </option>
              ))}
            </Select>
          </Field>
        </div>

        <div className="grid gap-6 sm:grid-cols-2">
          <Field
            label={copy.duration.label}
            required
            hint={
              durationLocked
                ? copy.duration.locked
                : fillPlaceholders(copy.duration.hint, {
                    min: String(DURATION_MIN_DAYS),
                    max: String(DURATION_MAX_DAYS),
                    recommended: String(DURATION_RECOMMENDED_DAYS),
                  })
            }
            error={errors.durationDays}
          >
            <TextInput
              inputMode="numeric"
              autoComplete="off"
              value={draft.durationDays}
              disabled={durationLocked}
              onChange={(event) =>
                change('durationDays', { ...draft, durationDays: event.target.value })
              }
              onBlur={autosave.flush}
            />
          </Field>

          <Field
            label={copy.schedule.label}
            hint={copy.schedule.hint}
            error={errors.scheduledLaunchAt}
          >
            <TextInput
              type="datetime-local"
              value={draft.scheduledLaunchAt}
              onChange={(event) =>
                change('scheduledLaunchAt', { ...draft, scheduledLaunchAt: event.target.value })
              }
              onBlur={autosave.flush}
            />
          </Field>
        </div>

        <div className="rounded-lg border border-white/8 bg-surface-2 p-5">
          {/*
            A switch, not a checkbox: this takes effect on the campaign rather
            than being collected for later, and `role="switch"` is what makes a
            screen reader say "on" instead of "checked" (docs/ui-kit.md §7.13).
          */}
          <Switch
            checked={draft.latePledgeEnabled}
            label={copy.latePledge.label}
            aria-describedby={latePledgeHintId}
            onCheckedChange={(checked) =>
              change('latePledgeEnabled', { ...draft, latePledgeEnabled: checked })
            }
          />
          <p id={latePledgeHintId} className="mt-2 text-[13px] text-white/64">
            {copy.latePledge.hint}
          </p>
        </div>

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
    </EditorShell>
  );
}
