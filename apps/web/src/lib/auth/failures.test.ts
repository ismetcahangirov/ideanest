import { describe, expect, it } from 'vitest';
import { ApiError } from '../api/problem';
import { describeAuthFailure, fieldErrorsOf } from './failures';
import { authFailuresCopyFrom } from '../i18n/auth-copy';
import { translatorFor } from '../../test-copy';

/*
 * The fallbacks the page would have resolved, built from `messages/en.json` by the same
 * function it calls — issue #324. The assertions below are therefore against the words the
 * screen will draw rather than against words retyped into this file.
 */
const COPY = authFailuresCopyFrom(translatorFor('auth'));

describe('describeAuthFailure', () => {
  it('shows the service’s own words rather than a generic apology', () => {
    const failure = describeAuthFailure(
      new ApiError(401, {
        title: 'Not authenticated',
        detail: 'That email address and password do not match an account.',
      }),
      COPY,
    );

    expect(failure.title).toBe('Not authenticated');
    expect(failure.detail).toBe('That email address and password do not match an account.');
    expect(failure.retryable).toBe(true);
  });

  /**
   * The refusal the issue names. `AuthExceptionHandler` answers 403 rather than 401 precisely
   * so a client stops offering to sign in again — the credentials were correct, and a retry
   * loop with a working password is the failure that produces support tickets.
   */
  it('marks a suspension as not retryable, and branches on the code rather than the prose', () => {
    const failure = describeAuthFailure(
      new ApiError(403, {
        code: 'ACCOUNT_SUSPENDED',
        title: 'Account suspended',
        detail: 'This account has been suspended. Contact support.',
      }),
      COPY,
    );

    expect(failure.retryable).toBe(false);
    expect(failure.detail).toContain('Contact support');
  });

  it('says how long a rate-limited caller has to wait', () => {
    const failure = describeAuthFailure(
      new ApiError(429, {
        title: 'Too many attempts',
        detail: 'Too many sign-in attempts.',
        retryAfterSeconds: 720,
      }),
      COPY,
    );

    // A rate limit expires; a suspension does not. The control stays for the first and is
    // withdrawn for the second.
    expect(failure.retryable).toBe(true);
    expect(failure.detail).toContain('12 minutes');
  });

  it('does not invent a wait it was not told about', () => {
    const failure = describeAuthFailure(new ApiError(429, { detail: 'Too many attempts.' }), COPY);

    expect(failure.detail).toBe('Too many attempts.');
    expect(failure.detail).not.toContain('minute');
  });

  it('reads a refusal with no body as the service being unreachable', () => {
    const failure = describeAuthFailure(new ApiError(503, null), COPY);

    expect(failure.title).toBe('The service could not be reached');
    expect(failure.detail).toContain('Nothing was submitted');
    expect(failure.retryable).toBe(true);
  });

  it('does not present our own bug as the reader’s details being wrong', () => {
    const failure = describeAuthFailure(new TypeError('fetch failed'), COPY);

    expect(failure.title).toBe('Something went wrong');
    expect(failure.retryable).toBe(true);
  });
});

describe('fieldErrorsOf', () => {
  it('is §10.4’s errors map, so a message lands beside the field it is about', () => {
    const errors = fieldErrorsOf(
      new ApiError(400, { errors: { password: 'A password must be at least 12 characters' } }),
    );

    expect(errors['password']).toBe('A password must be at least 12 characters');
  });

  it('is empty for anything that is not a validation failure, so a caller can render it unconditionally', () => {
    expect(fieldErrorsOf(new ApiError(401, { detail: 'no' }))).toEqual({});
    expect(fieldErrorsOf(new Error('boom'))).toEqual({});
  });
});
