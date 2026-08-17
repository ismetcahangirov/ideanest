import { describe, expect, it } from 'vitest';
import { ApiError, type Problem } from '../api/problem';
import { describeFailure } from './failure';

/**
 * The four refusals #52 answered and the contract did not name (#204).
 *
 * `CheckoutView.test.tsx` covers what the screen does with them; this covers
 * what they ARE, which is the part a second surface — #56's edit and cancel,
 * epic #59's charges — reads before it renders anything of its own. The
 * distinction that matters is between a refusal a backer can act on, one the
 * client should act on by itself, and one that is a bug report.
 */

function refusal(code: string, status: number, extra: Partial<Problem> = {}): ApiError {
  return new ApiError(status, { code, status, title: 'Refused', ...extra });
}

describe('IDEMPOTENT_REQUEST_IN_PROGRESS', () => {
  it('is a wait rather than a failure, and carries how long to wait for', () => {
    const failure = describeFailure(
      refusal('IDEMPOTENT_REQUEST_IN_PROGRESS', 409, { retryAfterSeconds: 2 }),
    );

    expect(failure.recovery).toBe('wait-and-retry');
    expect(failure.retryAfterMs).toBe(2000);
    // The key must be sent again unchanged: the request being waited on is the
    // one holding it, and a fresh key would be a second pledge.
    expect(failure.retireKey).toBe(false);
    expect(failure.clientBug).toBe(false);
  });

  it('reports no wait at all when the service asked for none', () => {
    // Null rather than a number invented here. How long to wait when nothing was
    // said is the caller's policy, not a fact about the response.
    expect(describeFailure(refusal('IDEMPOTENT_REQUEST_IN_PROGRESS', 409)).retryAfterMs).toBeNull();
  });

  it.each([["a string, '1'", '1'], ['a NaN', Number.NaN], ['a negative', -5]])(
    'ignores %s where the seconds should be, rather than computing a wait from it',
    (_name, seconds) => {
      /*
       * The problem body is JSON that has been CAST, not parsed, so a field
       * outside the contract is exactly as possible as any other. A NaN wait
       * reaches `setTimeout`, which reads it as zero — and a considered pause
       * becomes a hot loop against an endpoint that is already busy.
       */
      const problem = {
        code: 'IDEMPOTENT_REQUEST_IN_PROGRESS',
        status: 409,
        retryAfterSeconds: seconds,
      } as unknown as Problem;

      expect(describeFailure(new ApiError(409, problem)).retryAfterMs).toBeNull();
    },
  );
});

describe('the idempotency header refusals', () => {
  it.each([['IDEMPOTENCY_KEY_REQUIRED'], ['IDEMPOTENCY_KEY_INVALID']])(
    'reports %s as a defect in this client',
    (code) => {
      const failure = describeFailure(refusal(code, 400));

      expect(failure.clientBug).toBe(true);
      // Nothing on the screen will help: the next request would carry the same
      // missing or malformed header, because the client is what is wrong.
      expect(failure.recovery).toBe('none');
      expect(failure.title).not.toBe('Refused');
      expect(failure.detail).toMatch(/fault in this site/);
    },
  );
});

describe('PLEDGE_MODIFIED', () => {
  it('is a reservation to make again, and does not claim to know what changed', () => {
    const failure = describeFailure(refusal('PLEDGE_MODIFIED', 409));

    expect(failure.recovery).toBe('redraft');
    expect(failure.clientBug).toBe(false);
    /*
     * The usual cause is §8.4's sweep expiring the draft as it was confirmed,
     * and the service deliberately will not say so: the exception it catches is
     * a broad type, and a cause it had inferred rather than observed would be a
     * lie the first time something else writes to a pledge. This wording keeps
     * that honesty instead of restating it as an expiry.
     */
    expect(failure.detail).toMatch(/most often the five-minute hold running out/);
    expect(failure.detail).toMatch(/no card was involved/);
  });
});

describe('a code this build has never heard of', () => {
  it("still falls through to the service's own prose", () => {
    const failure = describeFailure(
      new ApiError(409, {
        code: 'SOMETHING_NEW',
        status: 409,
        title: 'A newer refusal',
        detail: 'What it says.',
      }),
    );

    // The honest answer for a refusal nobody here has wording for: the service
    // knows which of its rules refused the request and this module does not.
    expect(failure.title).toBe('A newer refusal');
    expect(failure.detail).toBe('What it says.');
    expect(failure.clientBug).toBe(false);
  });
});
