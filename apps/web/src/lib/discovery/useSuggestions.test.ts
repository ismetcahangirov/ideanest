import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, renderHook } from '@testing-library/react';
import { ApiError } from '../api/problem';
import { getSuggestions, type Suggestion } from './suggest';
import { SUGGEST_DEBOUNCE_MS, useSuggestions } from './useSuggestions';

/**
 * The debounce and the out-of-order guard, on a fake clock.
 *
 * THESE ARE HERE RATHER THAN IN THE COMPONENT TEST because they are about
 * timing, and timing asserted against wall-clock time is a test that passes on
 * a fast machine and fails on a loaded one. `SearchBox.test.tsx` covers what
 * the reader sees; this covers how many requests were sent, and when, to the
 * millisecond.
 *
 * Fake timers are used through `renderHook` rather than through a rendered
 * component: `@testing-library/react`'s async wrapper and vitest's fake clock
 * do not cooperate — `await user.type(...)` never settles — so the interactive
 * half of the suite runs on the real clock and this half does not touch the DOM.
 */

vi.mock('./suggest', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./suggest')>()),
  getSuggestions: vi.fn(),
}));

const suggestMock = vi.mocked(getSuggestions);

const CAMPAIGN: Suggestion = {
  kind: 'campaign',
  label: 'Oyun gecəsi dəsti',
  slug: 'oyun-gecesi-desti',
  parentSlug: 'sound-lab',
};

const CATEGORY: Suggestion = { kind: 'category', label: 'Games', slug: 'games' };

/** Lets the debounce elapse and the stubbed response settle. */
async function tick(ms = SUGGEST_DEBOUNCE_MS): Promise<void> {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(ms);
  });
}

beforeEach(() => {
  vi.useFakeTimers();
  vi.clearAllMocks();
  suggestMock.mockResolvedValue([]);
});

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});

describe('useSuggestions', () => {
  it('sends one request for a burst of keystrokes, not one per key', async () => {
    const { rerender } = renderHook(({ text }) => useSuggestions(text), {
      initialProps: { text: '' },
    });

    // Eight keystrokes, each 40ms apart — inside the quiet period throughout.
    for (const text of ['c', 'ce', 'cer', 'cera', 'ceram', 'cerami', 'ceramic', 'ceramics']) {
      rerender({ text });
      await tick(40);
    }
    expect(suggestMock).not.toHaveBeenCalled();

    await tick();

    expect(suggestMock).toHaveBeenCalledTimes(1);
    expect(suggestMock.mock.calls[0]?.[0]).toBe('ceramics');
  });

  it('waits the whole quiet period and no longer', async () => {
    const { rerender } = renderHook(({ text }) => useSuggestions(text), {
      initialProps: { text: '' },
    });

    rerender({ text: 'ceramics' });

    await tick(SUGGEST_DEBOUNCE_MS - 1);
    expect(suggestMock).not.toHaveBeenCalled();

    await tick(1);
    expect(suggestMock).toHaveBeenCalledTimes(1);
  });

  it('never asks about a fragment the endpoint cannot answer', async () => {
    const { rerender } = renderHook(({ text }) => useSuggestions(text), {
      initialProps: { text: '' },
    });

    // `SuggestQuery.MIN_LENGTH` is two: a blank box and a single character are
    // both answered with an empty list, so neither is worth a request — and
    // both are states every visitor passes through on every session.
    rerender({ text: 'c' });
    await tick();
    expect(suggestMock).not.toHaveBeenCalled();

    rerender({ text: '  ' });
    await tick();
    expect(suggestMock).not.toHaveBeenCalled();

    rerender({ text: 'ce' });
    await tick();
    expect(suggestMock).toHaveBeenCalledTimes(1);
  });

  it('clears what it holds when the fragment drops below the minimum', async () => {
    suggestMock.mockResolvedValue([CAMPAIGN]);

    const { result, rerender } = renderHook(({ text }) => useSuggestions(text), {
      initialProps: { text: 'ceramics' },
    });

    await tick();
    expect(result.current.items).toHaveLength(1);

    rerender({ text: '' });
    await tick(0);

    expect(result.current.items).toHaveLength(0);
    expect(result.current.status).toBe('idle');
  });

  /**
   * THE ONE THIS FILE EXISTS FOR.
   *
   * "ce" matches a large part of the platform and is slow for exactly that
   * reason; "ceramics" matches a handful and comes back at once. So the answer
   * for the fragment the reader has already typed past routinely lands AFTER
   * the answer for the fragment in the box — and without a guard it overwrites
   * it, leaving the reader looking at suggestions for a word that is no longer
   * there, with nothing on screen to say so.
   */
  it('drops a stale response that lands after a newer one', async () => {
    let answerStale: ((items: readonly Suggestion[]) => void) | null = null;

    suggestMock.mockImplementation((text: string) =>
      text === 'ce'
        ? new Promise<readonly Suggestion[]>((resolve) => {
            answerStale = resolve;
          })
        : Promise.resolve([CAMPAIGN]),
    );

    const { result, rerender } = renderHook(({ text }) => useSuggestions(text), {
      initialProps: { text: 'ce' },
    });

    await tick();
    expect(suggestMock).toHaveBeenCalledWith('ce', expect.anything());
    expect(result.current.status).toBe('loading');

    rerender({ text: 'ceramics' });
    await tick();

    expect(result.current.items).toEqual([CAMPAIGN]);

    // The earlier request finally answers, with a completely different list.
    await act(async () => {
      answerStale?.([CATEGORY]);
      await vi.advanceTimersByTimeAsync(0);
    });

    expect(result.current.items).toEqual([CAMPAIGN]);
    expect(result.current.status).toBe('ready');
  });

  it('drops a stale FAILURE too, so an old refusal cannot break a working list', async () => {
    let failStale: ((cause: unknown) => void) | null = null;

    suggestMock.mockImplementation((text: string) =>
      text === 'ce'
        ? new Promise<readonly Suggestion[]>((_resolve, reject) => {
            failStale = reject;
          })
        : Promise.resolve([CAMPAIGN]),
    );

    const { result, rerender } = renderHook(({ text }) => useSuggestions(text), {
      initialProps: { text: 'ce' },
    });
    await tick();

    rerender({ text: 'ceramics' });
    await tick();
    expect(result.current.status).toBe('ready');

    await act(async () => {
      failStale?.(new ApiError(500, null));
      await vi.advanceTimersByTimeAsync(0);
    });

    expect(result.current.status).toBe('ready');
    expect(result.current.items).toEqual([CAMPAIGN]);
  });

  it('reports a refusal with the problem the service sent', async () => {
    const problem = new ApiError(400, { code: 'DISCOVERY_VALUE_UNKNOWN', detail: 'No.' });
    suggestMock.mockRejectedValue(problem);

    const { result } = renderHook(() => useSuggestions('ceramics'));
    await tick();

    expect(result.current.status).toBe('failed');
    expect(result.current.error).toBe(problem);
    // A stale list under a failure is a list the reader will act on.
    expect(result.current.items).toHaveLength(0);
  });

  it('is not put into a failed state by its own cancellation', async () => {
    suggestMock.mockImplementation(
      (_text: string, options?: { signal?: AbortSignal }) =>
        new Promise<readonly Suggestion[]>((_resolve, reject) => {
          options?.signal?.addEventListener('abort', () =>
            reject(new DOMException('Aborted', 'AbortError')),
          );
        }),
    );

    const { result, rerender } = renderHook(({ text }) => useSuggestions(text), {
      initialProps: { text: 'ce' },
    });
    await tick();

    rerender({ text: 'ceramics' });
    await tick(0);

    expect(result.current.status).not.toBe('failed');
    expect(result.current.error).toBeNull();
  });
});
