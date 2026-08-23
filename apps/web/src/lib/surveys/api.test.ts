import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { setAccessToken } from '../api/access-token';
import {
  answerFor,
  listMySurveys,
  needsAnAnswer,
  orderedQuestions,
  respondToSurvey,
  type BackerSurvey,
  type SurveyQuestion,
} from './api';

/**
 * §4.8's PM-05 and PM-06 — issue #289.
 *
 * WHAT THESE COVER:
 *
 *   - **an unpositioned question does not jump to the top.** `position` is nullable on the
 *     wire, and a sort treating null as zero would silently reorder somebody else's survey —
 *     which is invisible until a creator asks why the last question is first.
 *   - "needs an answer" is open AND unanswered. A closed survey wants nothing whatever its
 *     state, and conflating the two puts a badge on a screen asking for something nobody can
 *     give.
 *   - the pledge travels in the body, because one account can hold two pledges on one
 *     campaign and the survey is asked of a pledge.
 */

const originalFetch = globalThis.fetch;

function question(overrides: Partial<SurveyQuestion> & Pick<SurveyQuestion, 'id'>): SurveyQuestion {
  return {
    position: null,
    prompt: 'A question',
    helpText: null,
    type: 'TEXT',
    required: false,
    choices: [],
    rewardTierId: null,
    ...overrides,
  };
}

function survey(overrides: Partial<BackerSurvey> = {}): BackerSurvey {
  return {
    surveyId: 'survey-1',
    projectId: 'project-1',
    pledgeId: 'pledge-1',
    title: 'Pick a colour',
    message: null,
    respondBy: null,
    open: true,
    answered: false,
    submittedAt: null,
    questions: [],
    answers: [],
    ...overrides,
  };
}

beforeEach(() => setAccessToken('a-token'));
afterEach(() => {
  setAccessToken(null);
  globalThis.fetch = originalFetch;
  vi.restoreAllMocks();
});

describe('orderedQuestions', () => {
  it('sorts by position and keeps arrival order for a tie', () => {
    const ordered = orderedQuestions(
      survey({
        questions: [
          question({ id: 'c', position: 2 }),
          question({ id: 'a', position: 1 }),
          question({ id: 'b', position: 1 }),
        ],
      }),
    );

    expect(ordered.map((q) => q.id)).toEqual(['a', 'b', 'c']);
  });

  it('puts an unpositioned question last rather than first', () => {
    const ordered = orderedQuestions(
      survey({
        questions: [question({ id: 'loose' }), question({ id: 'first', position: 1 })],
      }),
    );

    expect(ordered.map((q) => q.id)).toEqual(['first', 'loose']);
  });
});

describe('answerFor', () => {
  it('returns the stored value, and an empty list where there is none', () => {
    const one = survey({ answers: [{ questionId: 'q1', value: ['Blue'] }] });

    expect(answerFor(one, 'q1')).toEqual(['Blue']);
    expect(answerFor(one, 'q2')).toEqual([]);
  });
});

describe('needsAnAnswer', () => {
  it('is true only while a survey is open and unanswered', () => {
    expect(needsAnAnswer(survey({ open: true, answered: false }))).toBe(true);
    expect(needsAnAnswer(survey({ open: true, answered: true }))).toBe(false);
    expect(needsAnAnswer(survey({ open: false, answered: false }))).toBe(false);
  });
});

describe('the endpoints', () => {
  it('reads an absent list as empty rather than undefined', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(
        async () =>
          new Response('{}', { status: 200, headers: { 'content-type': 'application/json' } }),
      ),
    );

    expect(await listMySurveys()).toEqual([]);
  });

  it('names the pledge the answers belong to', async () => {
    // The stub declares its parameters, or `mock.calls` is typed `[]` and the body below
    // cannot be read without casting through `unknown`.
    const send = vi.fn(
      async (_path: string, _init?: RequestInit) =>
        new Response(JSON.stringify(survey({ answered: true })), {
          status: 200,
          headers: { 'content-type': 'application/json' },
        }),
    );
    vi.stubGlobal('fetch', send);

    await respondToSurvey('survey-1', 'pledge-9', [{ questionId: 'q1', value: ['Blue'] }]);

    expect(send.mock.calls[0]?.[0]).toBe('/v1/surveys/survey-1/respond');
    expect(JSON.parse(String(send.mock.calls[0]?.[1]?.body))).toEqual({
      pledgeId: 'pledge-9',
      answers: [{ questionId: 'q1', value: ['Blue'] }],
    });
  });
});
