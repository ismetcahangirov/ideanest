import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ApiError } from '../../lib/api/problem';
import { readReconciliation, runReconciliation } from '../../lib/admin/reconciliation';
import type { ReconciliationReport } from '../../lib/admin/reconciliation';
import { ReconciliationPanel } from './ReconciliationPanel';
import { translatorFor } from '../../test-copy';
import { consoleChromeCopyFrom } from '../../lib/i18n/admin/common-copy';
import { reconciliationCopyFrom } from '../../lib/i18n/admin/money-copy';

/*
 * The copy is built from `messages/en.json` with the same builders the routes call, rather
 * than typed out here. `src/test-copy.ts` explains why at length: a suite that retyped the
 * sentences would still be green with the catalogue empty, which is precisely the defect
 * `catalogue.test.ts` exists to catch.
 */
const COPY = reconciliationCopyFrom(
  translatorFor('admin'),
  consoleChromeCopyFrom(translatorFor('admin'), translatorFor('common')),
);

/**
 * §4.11's AD-05 reconciliation screen — issue #106.
 *
 * <p>The assertions that carry the design, and each of them is a way this screen could tell
 * a member of finance something false about the platform's money:
 *
 * <ul>
 *   <li>{@link #aPassThatNeverRanIsNotBalanced} — an empty finding list is what a healthy
 *       platform produces AND what a check that stopped running produces. Collapsing the two
 *       would put "the books balance" on a screen that has checked nothing.
 *   <li>A finding is rendered as a sentence with the figures in it, not as a status colour.
 *   <li>A failed run leaves the report that was already on screen. Somebody looking at three
 *       findings who presses the button and loses their connection must not be shown a blank
 *       screen instead of the three findings.
 *   <li>Nothing on the screen offers to correct anything, because the service reports and
 *       never repairs.
 * </ul>
 */

vi.mock('../../lib/admin/reconciliation', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../lib/admin/reconciliation')>();
  return { ...actual, readReconciliation: vi.fn(), runReconciliation: vi.fn() };
});

const readMock = vi.mocked(readReconciliation);
const runMock = vi.mocked(runReconciliation);

function report(overrides: Partial<ReconciliationReport> = {}): ReconciliationReport {
  return {
    hasRun: true,
    runAt: '2026-08-27T02:30:00.000Z',
    accountsChecked: 12,
    balanced: true,
    findings: [],
    ...overrides,
  };
}

beforeEach(() => {
  readMock.mockReset();
  runMock.mockReset();
});

describe('the reconciliation panel', () => {
  it('says the books balance, and how much was checked', async () => {
    readMock.mockResolvedValue(report());

    render(<ReconciliationPanel copy={COPY} />);

    expect(await screen.findByText(/The books balance/i)).toBeInTheDocument();
    expect(screen.getByText(/12 account positions checked/i)).toBeInTheDocument();
  });

  /**
   * The one this screen exists to get right.
   *
   * <p>`balanced: true, findings: []` is the service's answer both for a platform whose
   * books are fine and for a replica that has reconciled nothing since it started. Without
   * `hasRun` those are one response, and the screen would report the second as the first.
   */
  it('does not report a platform nobody has checked as balanced', async () => {
    readMock.mockResolvedValue(report({ hasRun: false, runAt: null, accountsChecked: 0 }));

    render(<ReconciliationPanel copy={COPY} />);

    expect(await screen.findByText(/Nothing has been reconciled/i)).toBeInTheDocument();
    expect(screen.queryByText(/The books balance/i)).not.toBeInTheDocument();
  });

  it('says an empty platform is balanced rather than unchecked', async () => {
    readMock.mockResolvedValue(report({ accountsChecked: 0 }));

    render(<ReconciliationPanel copy={COPY} />);

    expect(await screen.findByText(/The books balance/i)).toBeInTheDocument();
    expect(screen.getByText(/holds no money in any account yet/i)).toBeInTheDocument();
  });

  it('renders a finding as a sentence with its figures, and says what it means', async () => {
    readMock.mockResolvedValue(
      report({
        balanced: false,
        findings: [
          {
            kind: 'DISAGREES_WITH_PAYMENTS',
            currency: 'AZN',
            detail: 'The ledger holds 900.00 and the transactions say 1000.00',
          },
        ],
      }),
    );

    render(<ReconciliationPanel copy={COPY} />);

    expect(await screen.findByText(/The books do not balance/i)).toBeInTheDocument();
    expect(
      screen.getByText('The ledger holds 900.00 and the transactions say 1000.00'),
    ).toBeInTheDocument();
    // The kind as a title somebody can act on, not as a code they have to look up.
    expect(screen.getByText(/The ledger and the payment records disagree/i)).toBeInTheDocument();
    expect(screen.getByText(/never made at all/i)).toBeInTheDocument();
  });

  it('never offers to correct anything', async () => {
    readMock.mockResolvedValue(
      report({
        balanced: false,
        findings: [{ kind: 'UNBALANCED', currency: 'AZN', detail: 'net 0.01' }],
      }),
    );

    render(<ReconciliationPanel copy={COPY} />);
    await screen.findByText(/The books do not balance/i);

    // The service reports and never repairs, because the correcting entry depends on which
    // of a dozen things went wrong. A control here would be the interface promising
    // something the platform deliberately does not do.
    for (const label of [/resolve/i, /fix/i, /correct/i, /repair/i]) {
      expect(screen.queryByRole('button', { name: label })).not.toBeInTheDocument();
    }
    expect(screen.getByText(/Nothing has been corrected automatically/i)).toBeInTheDocument();
  });

  it('replaces the report with what a run just found', async () => {
    readMock.mockResolvedValue(report());
    runMock.mockResolvedValue(
      report({
        balanced: false,
        accountsChecked: 13,
        findings: [{ kind: 'IMPOSSIBLE_SIGN', currency: 'AZN', detail: 'escrow is negative' }],
      }),
    );

    render(<ReconciliationPanel copy={COPY} />);
    await screen.findByText(/The books balance/i);

    await userEvent.click(screen.getByRole('button', { name: /Check again now/i }));

    expect(await screen.findByText(/The books do not balance/i)).toBeInTheDocument();
    expect(screen.getByText('escrow is negative')).toBeInTheDocument();
    // One request, not two: the run answers with the report, so re-reading would spend a
    // round trip to be told what the reader just caused.
    expect(readMock).toHaveBeenCalledTimes(1);
  });

  it('keeps the report on screen when a run fails', async () => {
    readMock.mockResolvedValue(
      report({
        balanced: false,
        findings: [{ kind: 'UNBALANCED', currency: 'AZN', detail: 'net 0.01' }],
      }),
    );
    runMock.mockRejectedValue(new ApiError(503, null));

    render(<ReconciliationPanel copy={COPY} />);
    await screen.findByText(/The books do not balance/i);

    await userEvent.click(screen.getByRole('button', { name: /Check again now/i }));

    await waitFor(() => expect(screen.getByText(/That check did not run/i)).toBeInTheDocument());
    // Still there. Blanking a discrepancy because a retry failed would hide the one thing
    // the reader came for.
    expect(screen.getByText('net 0.01')).toBeInTheDocument();
  });

  it('refuses honestly when the account may not read finance', async () => {
    readMock.mockRejectedValue(new ApiError(403, null));

    render(<ReconciliationPanel copy={COPY} />);

    expect(await screen.findByText(/You do not work here/i)).toBeInTheDocument();
  });
});
