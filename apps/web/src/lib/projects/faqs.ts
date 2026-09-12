import type { FaqErrorsCopy } from '../i18n/editor-copy';
import type { Locale } from '../i18n/locale';
import { fillPlaceholders } from '../i18n/placeholders';
import { pluralise, type PluralForms } from '../i18n/plurals';
import { characterCount } from './basics';
import {
  FAQ_ANSWER_MAX_CHARACTERS,
  FAQ_QUESTION_MAX_CHARACTERS,
  type NewProjectFaq,
  type ProjectFaq,
  type ProjectFaqPatch,
} from './api';

/**
 * The FAQ form's own state, and the rules §4.4 refuses an entry against.
 *
 * Separate from `api.ts` for the same reason `rewards.ts` is: that module is the
 * shape of the wire and this is the shape of a half-typed form. A draft holds
 * the strings the controls hold, so that "  " is a thing a creator can be part
 * way through typing rather than a value the client has already decided is
 * invalid.
 *
 * <h2>The bounds are the service's, restated so the creator meets them first</h2>
 *
 * The service refuses a blank question, a blank answer, a question over 200
 * characters and an answer over 4000, with an RFC 9457 problem detail. Every one
 * of those is checked here too — not instead. A creator who pastes 4200
 * characters and is told so before pressing Save has lost nothing; one who is
 * told by a round trip has lost the press. The service stays the authority: when
 * it refuses anyway, its own sentence wins (`fieldErrorsFrom`).
 */

export interface FaqDraft {
  question: string;
  answer: string;
}

export type FaqField = keyof FaqDraft;

export type FaqErrors = Partial<Record<FaqField, string>>;

export const EMPTY_FAQ: FaqDraft = Object.freeze({ question: '', answer: '' });

/** Whether a name the service used in a refusal is a control this form has. */
export function isFaqField(value: string): value is FaqField {
  return value === 'question' || value === 'answer';
}

export function faqDraftFrom(faq: ProjectFaq): FaqDraft {
  return { question: faq.question, answer: faq.answer };
}

/**
 * What is wrong with the draft, keyed by the control it is about.
 *
 * A message per field rather than a banner, because §7.13 puts the error on the
 * control: "that is too long" above a form with two fields in it is a sentence a
 * creator has to guess at.
 *
 * The over-length message says how many characters too many rather than how many
 * are allowed. The creator can already see the limit under the field; what they
 * cannot see is how much to cut.
 */
export function validateFaq(draft: FaqDraft, copy: FaqErrorsCopy, locale: Locale): FaqErrors {
  const errors: FaqErrors = {};

  const question = draft.question.trim();
  const answer = draft.answer.trim();

  /*
   * The overshoot declines, so it is a plural form rather than `${over === 1 ? '' : 's'}` —
   * which is the whole of English and none of Russian. The count is only known here, so the
   * form is picked here; `lib/i18n/plurals.ts` carries the argument, and the language is an
   * argument for the same reason the sentences are (#459).
   */
  const tooLong = (forms: PluralForms, over: number, max: number): string =>
    fillPlaceholders(pluralise(locale, forms, over), { max: String(max) });

  if (question === '') {
    errors.question = copy.questionMissing;
  } else {
    const over = characterCount(question) - FAQ_QUESTION_MAX_CHARACTERS;
    if (over > 0) {
      errors.question = tooLong(copy.questionTooLong, over, FAQ_QUESTION_MAX_CHARACTERS);
    }
  }

  if (answer === '') {
    errors.answer = copy.answerMissing;
  } else {
    const over = characterCount(answer) - FAQ_ANSWER_MAX_CHARACTERS;
    if (over > 0) {
      errors.answer = tooLong(copy.answerTooLong, over, FAQ_ANSWER_MAX_CHARACTERS);
    }
  }

  return errors;
}

/**
 * The creation body.
 *
 * Trimmed, because the service refuses a blank and leading whitespace is not
 * content — but only at the ends. The line breaks inside an answer are the only
 * structure plain text has, and the public tab renders them.
 */
export function newFaqFrom(draft: FaqDraft): NewProjectFaq {
  return { question: draft.question.trim(), answer: draft.answer.trim() };
}

/**
 * Only what changed.
 *
 * A merge patch that wrote both fields back unchanged would still move
 * `updated_at` for no reason, and would still be refused by any future field
 * lock — both for nothing. An empty patch is not sent at all; see
 * {@link isEmptyFaqPatch}.
 */
export function faqPatchFrom(draft: FaqDraft, faq: ProjectFaq): ProjectFaqPatch {
  const patch: ProjectFaqPatch = {};

  const question = draft.question.trim();
  const answer = draft.answer.trim();

  if (question !== faq.question) patch.question = question;
  if (answer !== faq.answer) patch.answer = answer;

  return patch;
}

export function isEmptyFaqPatch(patch: ProjectFaqPatch): boolean {
  return Object.keys(patch).length === 0;
}
