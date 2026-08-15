import {
  DEFAULT_CURRENCY,
  isSupportedCurrency,
  parseAmount,
  toMoney,
  type AmountRejection,
} from '../money';
import { COVER_MIN_HEIGHT, COVER_MIN_WIDTH, describeSize, meetsCoverMinimum } from './coverImage';
import type { CoverImage, ProjectEdit, ProjectPatch } from './api';

/**
 * The basics tab, as data: what the creator has typed, what is wrong with it,
 * and what that turns into on the wire.
 *
 * Kept out of the component because these are the rules of docs/architecture
 * §5.3, and rules that live inside a form are rules nobody can unit-test at the
 * boundaries. Sixty characters, one hundred and thirty-five characters, one day
 * and sixty days are exactly the places this fails, so they are exactly the
 * places worth testing.
 *
 * THE CLIENT VALIDATES WHAT IS WRONG, NOT WHAT IS MISSING. §5.3 is a list of
 * SUBMISSION requirements, and a draft is by definition incomplete — a form
 * that reports "summary is required" the moment a project is created is a form
 * that shouts at somebody for not having finished yet. Completeness is the
 * checklist's job (#37), which reports it as progress rather than as failure and
 * is re-checked server-side by `POST /submit`. So: too long, out of range,
 * unreadable, or too small is an error here; not yet filled in is not.
 *
 * The one exception is the title. A project with no title cannot be listed
 * anywhere, `title` is `varchar(60) NOT NULL` behind the endpoint, and the
 * creator supplied one at creation — so clearing it is a mistake being made now
 * rather than work not yet done.
 */

export const TITLE_MAX_CHARACTERS = 60;
export const BLURB_MAX_CHARACTERS = 135;
export const DURATION_MIN_DAYS = 1;
export const DURATION_MAX_DAYS = 60;
/** §5.3: "30 recommended". Shown as guidance, never enforced. */
export const DURATION_RECOMMENDED_DAYS = 30;

/**
 * Characters, counted the way the database counts them.
 *
 * `String.prototype.length` counts UTF-16 code units, so an emoji or a
 * musical symbol counts as two and a creator would be told they had used 61
 * characters when Postgres — which counts `varchar(60)` in code points — was
 * perfectly happy with the string. `Array.from` iterates code points.
 *
 * It is still not grapheme clusters: a flag emoji or a combining accent is
 * several code points, and Postgres will count them the same several. Matching
 * the storage is the point; matching a human's idea of a letter would put the
 * counter and the constraint into disagreement.
 */
export function characterCount(value: string): number {
  return Array.from(value).length;
}

/**
 * The form's own state.
 *
 * Every field is the string the control holds, not the parsed value. A field
 * that stores a number cannot hold `"5."` while somebody is typing `"5.5"`, and
 * one that stores a `Date` cannot hold a half-entered date at all — so the
 * draft keeps text and parsing happens on the way out.
 */
export interface BasicsDraft {
  title: string;
  blurb: string;
  categoryId: string;
  subcategoryId: string;
  goalAmount: string;
  currency: string;
  durationDays: string;
  /** The `datetime-local` value: local wall-clock time, no offset. */
  scheduledLaunchAt: string;
  latePledgeEnabled: boolean;
  /**
   * The address as typed, which is not the same thing as the saved cover.
   *
   * A URL only becomes a cover once its intrinsic size has been read, and that
   * read is asynchronous (`coverImage.ts`). Keeping the text separate is what
   * lets the field hold a half-pasted address, or one that failed to load,
   * without destroying the cover that was already saved.
   */
  coverImageUrl: string;
  /** Measured and accepted, and therefore sendable. */
  coverImage: CoverImage | null;
}

/**
 * Field names as the API names them, because a 422's `errors` map is keyed by
 * them (docs/architecture.md §10.4) and a server message must be able to land on
 * the field it is about without a translation table in between.
 */
export type BasicsField =
  | 'title'
  | 'blurb'
  | 'categoryId'
  | 'subcategoryId'
  | 'goal'
  | 'durationDays'
  | 'scheduledLaunchAt'
  | 'coverImage'
  | 'latePledgeEnabled';

export type BasicsErrors = Partial<Record<BasicsField, string>>;

export function draftFromProject(project: ProjectEdit): BasicsDraft {
  return {
    title: project.title,
    blurb: project.blurb ?? '',
    categoryId: project.categoryId ?? '',
    subcategoryId: project.subcategoryId ?? '',
    goalAmount: project.goal?.amount ?? '',
    currency: project.goal?.currency ?? DEFAULT_CURRENCY,
    durationDays: project.durationDays === null ? '' : (project.durationDays?.toString() ?? ''),
    scheduledLaunchAt: toDateTimeLocal(project.scheduledLaunchAt),
    latePledgeEnabled: project.latePledgeEnabled,
    coverImageUrl: project.coverImage?.url ?? '',
    coverImage: project.coverImage ?? null,
  };
}

/* -------------------------------------------------------------------------
 * Dates
 *
 * `<input type="datetime-local">` speaks local wall-clock time with no offset,
 * and the API speaks ISO 8601 in UTC (§10.3). The conversion is one place, in
 * both directions, because a scheduled launch that is four hours out is a
 * campaign that opens while its creator is asleep.
 * ---------------------------------------------------------------------- */

function pad(value: number): string {
  return value.toString().padStart(2, '0');
}

/** An instant from the API, as the local value the control expects. */
export function toDateTimeLocal(iso: string | null | undefined): string {
  if (!iso) return '';

  const when = new Date(iso);
  if (Number.isNaN(when.getTime())) return '';

  return (
    `${when.getFullYear()}-${pad(when.getMonth() + 1)}-${pad(when.getDate())}` +
    `T${pad(when.getHours())}:${pad(when.getMinutes())}`
  );
}

/**
 * The control's local value, as the instant the API stores.
 *
 * `new Date('2026-09-01T10:00')` is parsed as local time, which is exactly what
 * the creator meant by it, and `toISOString` moves it to UTC without arithmetic
 * of ours.
 */
export function fromDateTimeLocal(value: string): string | null {
  if (value.trim() === '') return null;

  const when = new Date(value);
  if (Number.isNaN(when.getTime())) return null;

  return when.toISOString();
}

/* -------------------------------------------------------------------------
 * Validation
 * ---------------------------------------------------------------------- */

const AMOUNT_MESSAGE: Record<AmountRejection, string> = {
  empty: 'Enter the amount you need to raise.',
  'not-a-number': 'Enter the goal in digits, for example 5000.00.',
  comma: 'Use a full stop for the decimal point, for example 5000.00.',
  'too-many-decimals': 'A goal has at most two decimal places.',
  'too-large': 'That goal is larger than the platform can hold.',
  'not-positive': 'A goal has to be more than zero.',
};

/** Integer days only — `"14.5"` and `"14 days"` are both refusals. */
const WHOLE_DAYS = /^\d+$/;

export interface ValidationContext {
  /** Injected so the "already in the past" boundary is testable. */
  now?: Date;
}

export function validateBasics(draft: BasicsDraft, context: ValidationContext = {}): BasicsErrors {
  const errors: BasicsErrors = {};
  const now = context.now ?? new Date();

  const titleLength = characterCount(draft.title.trim());
  if (titleLength === 0) {
    errors.title = 'A project needs a title.';
  } else if (titleLength > TITLE_MAX_CHARACTERS) {
    errors.title = `A title is ${TITLE_MAX_CHARACTERS} characters or fewer. Remove ${
      titleLength - TITLE_MAX_CHARACTERS
    }.`;
  }

  const blurbLength = characterCount(draft.blurb);
  if (blurbLength > BLURB_MAX_CHARACTERS) {
    errors.blurb = `A summary is ${BLURB_MAX_CHARACTERS} characters or fewer. Remove ${
      blurbLength - BLURB_MAX_CHARACTERS
    }.`;
  }

  // A subcategory belongs to a category, so one without the other is not a
  // half-finished choice — it is a contradiction, and the server would refuse it.
  if (draft.subcategoryId !== '' && draft.categoryId === '') {
    errors.subcategoryId = 'Choose a category first.';
  }

  if (draft.goalAmount.trim() !== '') {
    const parsed = parseAmount(draft.goalAmount);
    if (!parsed.ok) errors.goal = AMOUNT_MESSAGE[parsed.reason];
  }

  if (!isSupportedCurrency(draft.currency)) {
    errors.goal = errors.goal ?? 'Choose a currency the platform can collect in.';
  }

  const days = draft.durationDays.trim();
  if (days !== '') {
    if (!WHOLE_DAYS.test(days)) {
      errors.durationDays = 'Enter the duration as a whole number of days.';
    } else {
      const value = Number.parseInt(days, 10);
      if (value < DURATION_MIN_DAYS || value > DURATION_MAX_DAYS) {
        errors.durationDays = `A campaign runs for ${DURATION_MIN_DAYS} to ${DURATION_MAX_DAYS} days.`;
      }
    }
  }

  if (draft.scheduledLaunchAt.trim() !== '') {
    const instant = fromDateTimeLocal(draft.scheduledLaunchAt);
    if (instant === null) {
      errors.scheduledLaunchAt = 'Enter a date and time.';
    } else if (new Date(instant).getTime() <= now.getTime()) {
      errors.scheduledLaunchAt = 'Choose a date and time in the future.';
    }
  }

  if (draft.coverImage !== null && !meetsCoverMinimum(draft.coverImage)) {
    errors.coverImage =
      `A cover image is at least ${COVER_MIN_WIDTH}×${COVER_MIN_HEIGHT} pixels. ` +
      `This one is ${describeSize(draft.coverImage)}.`;
  }

  return errors;
}

/* -------------------------------------------------------------------------
 * Draft to patch
 * ---------------------------------------------------------------------- */

/**
 * The patch for one field, or `null` when what is in that field is not worth
 * sending.
 *
 * ONE FIELD AT A TIME, and never the whole draft. `PATCH /v1/projects/{id}` has
 * merge-patch semantics: every key present is written. A patch built from the
 * entire form would rewrite the goal every time the title changed, which turns
 * a locked field (#36) into a 409 for no reason and, worse, would let a stale
 * value in an untouched control overwrite an edit made in another tab.
 *
 * `null` versus an absent key is the difference between clearing a field and
 * leaving it alone, so an emptied optional field sends an explicit `null`.
 */
export function patchForField(field: BasicsField, draft: BasicsDraft): ProjectPatch | null {
  switch (field) {
    case 'title': {
      const title = draft.title.trim();
      // Refuse rather than clear: `title` is NOT NULL, and an empty PATCH here
      // would be answered with a 400 the creator cannot act on.
      if (title === '' || characterCount(title) > TITLE_MAX_CHARACTERS) return null;
      return { title };
    }

    case 'blurb': {
      const blurb = draft.blurb.trim();
      if (characterCount(blurb) > BLURB_MAX_CHARACTERS) return null;
      return { blurb: blurb === '' ? null : blurb };
    }

    case 'categoryId': {
      // The subcategory travels with it. A subcategory of a category that is no
      // longer selected is orphaned data, and merge-patch needs to be told so
      // explicitly.
      return {
        categoryId: draft.categoryId === '' ? null : draft.categoryId,
        subcategoryId: draft.subcategoryId === '' ? null : draft.subcategoryId,
      };
    }

    case 'subcategoryId':
      if (draft.subcategoryId !== '' && draft.categoryId === '') return null;
      return { subcategoryId: draft.subcategoryId === '' ? null : draft.subcategoryId };

    case 'goal': {
      if (!isSupportedCurrency(draft.currency)) return null;
      if (draft.goalAmount.trim() === '') return { goal: null };

      const parsed = parseAmount(draft.goalAmount);
      if (!parsed.ok) return null;

      // `toMoney` formats through `Decimal.toFixed`, so the string the server
      // receives is the one the creator typed at the scale the column holds —
      // it has never been a JavaScript number.
      return { goal: toMoney(parsed.value, draft.currency) };
    }

    case 'durationDays': {
      const days = draft.durationDays.trim();
      if (days === '') return { durationDays: null };
      if (!WHOLE_DAYS.test(days)) return null;

      const value = Number.parseInt(days, 10);
      if (value < DURATION_MIN_DAYS || value > DURATION_MAX_DAYS) return null;

      return { durationDays: value };
    }

    case 'scheduledLaunchAt': {
      if (draft.scheduledLaunchAt.trim() === '') return { scheduledLaunchAt: null };

      const instant = fromDateTimeLocal(draft.scheduledLaunchAt);
      return instant === null ? null : { scheduledLaunchAt: instant };
    }

    case 'latePledgeEnabled':
      return { latePledgeEnabled: draft.latePledgeEnabled };

    case 'coverImage':
      return { coverImage: draft.coverImage };
  }
}
