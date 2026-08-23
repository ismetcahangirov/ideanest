import { authorizedFetch } from '../api/client';
import { errorFrom } from '../api/problem';

/**
 * §4.8's PM-05 and PM-06 — the surveys a backer is being asked, and their answers.
 *
 * <h2>Two endpoints and one shape</h2>
 *
 * `GET /v1/me/surveys` returns the whole survey — questions, the reader's own answers, and
 * whether it is still open — and `POST /v1/surveys/{id}/respond` answers with the same shape
 * updated. So a submission needs no re-read to be correct on screen, and the form has one
 * type to render rather than a definition and a response that could disagree.
 *
 * <h2>Answering twice is the same row, not a second one</h2>
 *
 * `BackerSurveyController` answers 200 even on a first submission, deliberately: PM-06 makes
 * this one row that moves. The form therefore says **Save answers** rather than Submit, and
 * says so again after the first save.
 *
 * <h2>An ADDRESS question stores no answer</h2>
 *
 * The one question type that is a prompt rather than a field. `QuestionType.ADDRESS` explains
 * it: the answer is the pledge's row in `shipping_addresses` — encrypted at rest, validated,
 * lockable — and copying it into `survey_answers` would give the platform two addresses per
 * backer that can disagree, in a table §17.4's erasure does not know to look at. The form
 * points at `/pledges/{id}/address` (#290) instead of collecting one, and sends nothing for
 * that question.
 */

export type QuestionType = 'TEXT' | 'CHOICE' | 'MULTI_CHOICE' | 'DATE' | 'ADDRESS';

export interface SurveyQuestion {
  readonly id: string;
  readonly position: number | null;
  readonly prompt: string;
  readonly helpText: string | null;
  /** Widened to `string` so an unknown type from a newer service renders rather than throws. */
  readonly type: QuestionType | string;
  readonly required: boolean;
  readonly choices: readonly string[];
  readonly rewardTierId: string | null;
}

export interface SurveyAnswer {
  readonly questionId: string;
  /** Always a list, even for a single-value question — that is the wire shape. */
  readonly value: readonly string[];
}

export interface BackerSurvey {
  readonly surveyId: string;
  readonly projectId: string;
  readonly pledgeId: string;
  readonly title: string;
  readonly message: string | null;
  /** ISO-8601 instant, or `null` where the creator set no date. */
  readonly respondBy: string | null;
  /** Whether answers are still accepted. A closed survey is shown, read-only. */
  readonly open: boolean;
  readonly answered: boolean;
  readonly submittedAt: string | null;
  readonly questions: readonly SurveyQuestion[];
  readonly answers: readonly SurveyAnswer[];
}

/** Every survey this account is being asked — `GET /v1/me/surveys`. */
export async function listMySurveys(signal?: AbortSignal): Promise<readonly BackerSurvey[]> {
  const response = await authorizedFetch('/v1/me/surveys', { signal });
  if (!response.ok) throw await errorFrom(response);

  const body = (await response.json()) as { readonly surveys?: readonly BackerSurvey[] };
  return body.surveys ?? [];
}

/**
 * Records the answers — `POST /v1/surveys/{surveyId}/respond`.
 *
 * The pledge travels in the body because one account can hold two pledges on one campaign
 * and the survey is asked of a pledge, not of a person. It comes from the survey the service
 * itself returned rather than from anything the form chose.
 */
export async function respondToSurvey(
  surveyId: string,
  pledgeId: string,
  answers: readonly SurveyAnswer[],
): Promise<BackerSurvey> {
  const response = await authorizedFetch(
    `/v1/surveys/${encodeURIComponent(surveyId)}/respond`,
    {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ pledgeId, answers }),
    },
  );

  if (!response.ok) throw await errorFrom(response);
  return (await response.json()) as BackerSurvey;
}

/**
 * The questions in the order the creator wrote them.
 *
 * `position` is nullable on the wire and a sort that treats `null` as zero would silently
 * hoist an unpositioned question to the top. Unpositioned questions keep their arrival order
 * and sort after the positioned ones, which is the only ordering that cannot reorder somebody
 * else's survey.
 */
export function orderedQuestions(survey: BackerSurvey): readonly SurveyQuestion[] {
  return [...survey.questions]
    .map((question, index) => ({ question, index }))
    .sort((a, b) => {
      const left = a.question.position ?? Number.MAX_SAFE_INTEGER;
      const right = b.question.position ?? Number.MAX_SAFE_INTEGER;
      return left === right ? a.index - b.index : left - right;
    })
    .map((entry) => entry.question);
}

/** The reader's current answer to one question, as a list. Empty where they have not answered. */
export function answerFor(survey: BackerSurvey, questionId: string): readonly string[] {
  return survey.answers.find((answer) => answer.questionId === questionId)?.value ?? [];
}

/**
 * Whether this survey still wants something from the reader.
 *
 * Open **and** unanswered. A closed survey wants nothing whatever its state, and an answered
 * one can still be changed while it is open — which is a different sentence and a different
 * badge.
 */
export function needsAnAnswer(survey: BackerSurvey): boolean {
  return survey.open && !survey.answered;
}
