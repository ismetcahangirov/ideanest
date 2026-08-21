import { act } from 'react';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import { LiveFunding } from './LiveFunding';

/**
 * §12.1's counter, from the reader's side.
 *
 * The two properties that matter, and neither is visible in the pure functions
 * `lib/realtime/updates.test.ts` covers:
 *
 * - **The numbers are in the markup before anything connects**, which is what keeps this
 *   island from breaking #119. A crawler and a reader with no JavaScript see the campaign's
 *   real totals.
 * - **A window moves the amount and the percentage together.** A percentage frozen at the
 *   server's value while the amount beside it moved would be two numbers disagreeing on one
 *   page, which is worse than no live counter at all.
 */

const PROJECT = '0193f2a1-0000-7000-8000-000000000001';

/** A socket the test drives, standing in for the browser's. */
class FakeSocket {
  static last: FakeSocket | null = null;

  onopen: (() => void) | null = null;
  onmessage: ((event: { data: unknown }) => void) | null = null;
  onclose: (() => void) | null = null;
  closed = false;

  constructor(readonly url: string) {
    FakeSocket.last = this;
  }

  close(): void {
    this.closed = true;
  }

  /** What the flusher would have sent at the end of a window. */
  deliver(payload: unknown): void {
    this.onmessage?.({ data: JSON.stringify(payload) });
  }
}

const originalWebSocket = globalThis.WebSocket;

/*
 * The stand-in is installed on `globalThis` rather than injected through a prop, because what
 * is being asserted is that the hook reaches for the platform's `WebSocket` — a seam for the
 * test would be a seam production never uses, and the test would then pass whether or not the
 * real socket was ever opened.
 *
 * Cast through `unknown` rather than `any`: CLAUDE.md §3 forbids `any`, and the shape here is
 * genuinely not a `WebSocket` — it is the four members this component touches.
 */
type WebSocketGlobal = { WebSocket: unknown };

beforeEach(() => {
  FakeSocket.last = null;
  (globalThis as unknown as WebSocketGlobal).WebSocket = FakeSocket;
});

afterEach(() => {
  cleanup();
  (globalThis as unknown as WebSocketGlobal).WebSocket = originalWebSocket;
});

function renderFunding(realtimeOrigin: string | undefined) {
  return render(
    <LiveFunding
      projectId={PROJECT}
      goal={{ amount: '10000.00', currency: 'AZN' }}
      pledged={{ amount: '5000.00', currency: 'AZN' }}
      backersCount={40}
      realtimeOrigin={realtimeOrigin}
    />,
  );
}

describe('LiveFunding', () => {
  it('renders the server’s numbers with no socket configured', () => {
    renderFunding(undefined);

    expect(screen.getByText('50%')).toBeTruthy();
    expect(screen.getByText('40')).toBeTruthy();
    expect(FakeSocket.last).toBeNull();
  });

  it('opens a socket on the campaign’s counter channel when an origin is configured', () => {
    renderFunding('https://api.ideanest.az');

    expect(FakeSocket.last?.url).toBe(
      `wss://api.ideanest.az/v1/realtime?channel=${encodeURIComponent(`project:${PROJECT}`)}`,
    );
  });

  it('adds a window’s amount to the total and recomputes the percentage', () => {
    renderFunding('https://api.ideanest.az');

    act(() => {
      FakeSocket.last?.deliver({
        channel: `project:${PROJECT}`,
        pledges: 2,
        amount: { amount: '1000.00', currency: 'AZN' },
      });
    });

    expect(screen.getByText('60%')).toBeTruthy();
  });

  it('accumulates across windows rather than replacing', () => {
    renderFunding('https://api.ideanest.az');

    act(() => {
      FakeSocket.last?.deliver({
        channel: `project:${PROJECT}`,
        pledges: 1,
        amount: { amount: '1000.00', currency: 'AZN' },
      });
      FakeSocket.last?.deliver({
        channel: `project:${PROJECT}`,
        pledges: 1,
        amount: { amount: '2000.00', currency: 'AZN' },
      });
    });

    expect(screen.getByText('80%')).toBeTruthy();
  });

  /*
   * A window carries how many pledges were confirmed, and a pledge is not always a new backer:
   * somebody raising their pledge confirms again. Adding it would make the count drift upwards
   * with no way to correct itself, which is worse than one that is right at page load.
   */
  it('does not move the backer count', () => {
    renderFunding('https://api.ideanest.az');

    act(() => {
      FakeSocket.last?.deliver({
        channel: `project:${PROJECT}`,
        pledges: 5,
        amount: { amount: '125.00', currency: 'AZN' },
      });
    });

    expect(screen.getByText('40')).toBeTruthy();
  });

  it('ignores a frame that is not a message from this server', () => {
    renderFunding('https://api.ideanest.az');

    act(() => {
      FakeSocket.last?.onmessage?.({ data: 'not json' });
      FakeSocket.last?.onmessage?.({ data: 42 });
      FakeSocket.last?.deliver({ nonsense: true });
    });

    expect(screen.getByText('50%')).toBeTruthy();
  });

  it('closes the socket when the page goes away', () => {
    const view = renderFunding('https://api.ideanest.az');
    const socket = FakeSocket.last;

    view.unmount();

    expect(socket?.closed).toBe(true);
  });
});
