import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ApiError } from '../../lib/api/problem';
import { readTrail } from '../../lib/admin/audit';
import { listCollections, replaceCollection } from '../../lib/admin/curation';
import { readLedger } from '../../lib/admin/ledger';
import type { AdminCollection } from '../../lib/admin/curation';
import type { LedgerView } from '../../lib/admin/ledger';
import { AuditTrailView } from './AuditTrailView';
import { BadgeManager } from './BadgeManager';
import { LedgerExplorer } from './LedgerExplorer';
import { translatorFor } from '../../test-copy';
import {
  consoleChromeCopyFrom,
  noteDialogCopyFrom,
} from '../../lib/i18n/admin/common-copy';
import { curationChromeFrom } from '../../lib/i18n/admin/curation-copy';
import { badgeManagerCopyFrom } from '../../lib/i18n/admin/curation-copy';
import { auditTrailCopyFrom } from '../../lib/i18n/admin/platform-copy';
import { ledgerExplorerCopyFrom } from '../../lib/i18n/admin/money-copy';

/*
 * The copy is built from `messages/en.json` with the same builders the routes call, rather
 * than typed out here. `src/test-copy.ts` explains why at length: a suite that retyped the
 * sentences would still be green with the catalogue empty, which is precisely the defect
 * `catalogue.test.ts` exists to catch.
 */
const ADMIN = translatorFor('admin');
const CHROME = consoleChromeCopyFrom(ADMIN, translatorFor('common'));
const AUDIT = auditTrailCopyFrom(ADMIN, CHROME);
const LEDGER = ledgerExplorerCopyFrom(ADMIN, CHROME);
const BADGES = badgeManagerCopyFrom(ADMIN, curationChromeFrom(ADMIN, CHROME));
const NOTE = noteDialogCopyFrom(ADMIN, translatorFor('common'));

vi.mock('../../lib/admin/audit', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../lib/admin/audit')>();
  return { ...actual, readTrail: vi.fn() };
});

vi.mock('../../lib/admin/ledger', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../lib/admin/ledger')>();
  return { ...actual, readLedger: vi.fn() };
});

vi.mock('../../lib/admin/curation', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../lib/admin/curation')>();
  return { ...actual, listCollections: vi.fn(), replaceCollection: vi.fn() };
});

const readTrailMock = vi.mocked(readTrail);
const readLedgerMock = vi.mocked(readLedger);
const listCollectionsMock = vi.mocked(listCollections);
const replaceCollectionMock = vi.mocked(replaceCollection);

const TRANSACTION_ID = '0191f2ab-1234-7000-8000-000000000001';
const PROJECT_ID = '0191f2ab-1234-7000-8000-000000000002';

function ledgerView(overrides: Partial<LedgerView> = {}): LedgerView {
  return {
    postings: [
      {
        transactionId: TRANSACTION_ID,
        projectId: PROJECT_ID,
        createdAt: '2026-08-24T10:00:00.000Z',
        lines: [
          { account: 'escrow', direction: 'DEBIT', amount: { amount: '100.00', currency: 'AZN' } },
          {
            account: 'platform_fee',
            direction: 'CREDIT',
            amount: { amount: '100.00', currency: 'AZN' },
          },
        ],
        balanced: true,
      },
    ],
    balances: [
      { account: 'escrow', net: { amount: '100.00', currency: 'AZN' } },
      { account: 'platform_fee', net: { amount: '-100.00', currency: 'AZN' } },
    ],
    nextCursor: null,
    ...overrides,
  };
}

function collection(overrides: Partial<AdminCollection> = {}): AdminCollection {
  return {
    id: 'c-1',
    slug: 'autumn-picks',
    kind: 'staff_selection',
    copy: { az: { title: 'Autumn picks' } },
    grantsBadge: false,
    sortOrder: 10,
    opensAt: '2026-08-01T00:00:00.000Z',
    ...overrides,
  };
}

beforeEach(() => {
  vi.clearAllMocks();
  readTrailMock.mockResolvedValue({ entries: [], nextCursor: null });
  readLedgerMock.mockResolvedValue(ledgerView());
  listCollectionsMock.mockResolvedValue([collection()]);
});

/**
 * The console's read and curation screens — issues #300, #305 and #314.
 *
 * <p>Appearance is reviewed in Storybook. These cover the wiring that breaks silently: a
 * filter that narrows in the browser instead of at the service, a posting rendered with one
 * of its two sides, and a `PUT` that clears the six fields the form was not looking at. Every
 * one of those looks correct on screen and is wrong.
 */
describe('the ledger explorer', () => {
  it('shows both sides of a posting even when the filter matched one of them', async () => {
    const user = userEvent.setup();
    render(<LedgerExplorer copy={LEDGER} />);
    await screen.findByText('Escrow');

    await user.click(screen.getByRole('button', { name: 'Escrow' }));

    await waitFor(() => {
      expect(readLedgerMock).toHaveBeenLastCalledWith(
        expect.objectContaining({ account: 'escrow' }),
      );
    });

    // The filter decides which postings appear and never which half of one is shown. A
    // ledger rendering one side of a double entry would be showing a balance that does not
    // balance, which is the one thing this table exists to make impossible.
    expect(await screen.findAllByText('Platform fee')).not.toHaveLength(0);
    expect(screen.getAllByText('debit').length).toBe(1);
    expect(screen.getAllByText('credit').length).toBe(1);
  });

  it('keeps every account in the balance panel while the postings narrow', async () => {
    const user = userEvent.setup();
    render(<LedgerExplorer copy={LEDGER} />);
    await screen.findByText('Balances across the platform');

    await user.click(screen.getByRole('button', { name: 'Escrow' }));
    await waitFor(() => expect(readLedgerMock).toHaveBeenCalledTimes(2));

    // Filtering to escrow does not make the other accounts stop existing, and a one-line
    // balance panel would read as though it were the whole ledger. Scoped to the panel,
    // because the posting below it carries the same figures.
    const panel = screen.getByText('Balances across the platform').parentElement!;
    expect(within(panel).getByText('100.00 AZN')).toBeInTheDocument();
    expect(within(panel).getByText('-100.00 AZN')).toBeInTheDocument();
  });

  it('raises an alarm about a posting that does not balance', async () => {
    const view = ledgerView();
    readLedgerMock.mockResolvedValue({
      ...view,
      postings: [{ ...view.postings[0]!, balanced: false }],
    });
    render(<LedgerExplorer copy={LEDGER} />);

    // V41 refuses to commit one, so reaching this means a row arrived past both the
    // application and the database. It is an incident and the reader needs to know first.
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('A posting does not balance');
  });

  it('tells a reader who is not staff why, rather than showing an empty ledger', async () => {
    readLedgerMock.mockRejectedValue(new ApiError(403, { code: 'NOT_A_MODERATOR' }, 'no'));
    render(<LedgerExplorer copy={LEDGER} />);

    expect(await screen.findByText('You do not work here')).toBeInTheDocument();
    // And not an empty state, which on a ledger reads as "no money has moved".
    expect(screen.queryByText('Nothing has been posted yet')).toBeNull();
  });

  it('names the capability a colleague is short of, rather than calling them a stranger', async () => {
    readLedgerMock.mockRejectedValue(
      new ApiError(
        403,
        { code: 'INSUFFICIENT_STAFF_CAPABILITY', meta: { capability: 'VIEW_FINANCE' } },
        'no',
      ),
    );
    render(<LedgerExplorer copy={LEDGER} />);

    /*
     * Issue #400. `StaffDirectory.requireCapability` separates the two 403s deliberately and
     * says why: "a stranger is told they do not work here; a colleague is told which
     * authority this screen wanted". The console rendered the stranger's sentence for both,
     * so a moderator opening a money screen was told they were not a moderator — on a
     * console that had just loaded the moderation queue for them.
     */
    expect(await screen.findByText('This screen is not yours')).toBeInTheDocument();
    expect(screen.getByText(/VIEW_FINANCE/)).toBeInTheDocument();
    expect(screen.queryByText('You do not work here')).toBeNull();
  });
});

describe('the audit trail', () => {
  it('asks the service to narrow rather than filtering the page it holds', async () => {
    const user = userEvent.setup();
    render(<AuditTrailView copy={AUDIT} />);
    await screen.findByRole('button', { name: 'Everything' });

    await user.click(screen.getByRole('button', { name: 'Campaigns' }));

    // A chip that filtered twenty-five rows in the browser would say "3 results" about a
    // table with four thousand matching rows in it, which on an audit surface is a wrong
    // answer rather than a rough edge.
    await waitFor(() => {
      expect(readTrailMock).toHaveBeenLastCalledWith(
        expect.objectContaining({ entityType: 'project' }),
      );
    });
  });

  it('says an empty trail is empty rather than broken', async () => {
    render(<AuditTrailView copy={AUDIT} />);

    expect(await screen.findByText('Nothing has been recorded yet')).toBeInTheDocument();
  });
});

describe('the badge manager', () => {
  it('sends the whole collection back, not only the field it changed', async () => {
    const user = userEvent.setup();
    replaceCollectionMock.mockResolvedValue(collection({ grantsBadge: true }));
    render(<BadgeManager copy={BADGES} note={NOTE} />);
    await screen.findByRole('button', { name: 'Grant the badge' });

    await user.click(screen.getByRole('button', { name: 'Grant the badge' }));
    // The dialog asks for a reason before a change that affects every campaign in the list.
    const dialog = await screen.findByRole('dialog');
    await user.type(within(dialog).getByRole('textbox'), 'Selected for the autumn feature.');
    await user.click(within(dialog).getByRole('button', { name: 'Grant the badge' }));

    await waitFor(() => expect(replaceCollectionMock).toHaveBeenCalled());

    // `PUT` replaces the whole description. A body built from the one field the screen meant
    // to change would silently clear the window, the placement and the copy.
    expect(replaceCollectionMock).toHaveBeenCalledWith('autumn-picks', {
      kind: 'staff_selection',
      copy: { az: { title: 'Autumn picks' } },
      cover: null,
      opensAt: '2026-08-01T00:00:00.000Z',
      closesAt: null,
      grantsBadge: true,
      sortOrder: 10,
    });
  });

  it('says an unpublished collection badges nothing yet', async () => {
    render(<BadgeManager copy={BADGES} note={NOTE} />);

    // A curator who turned the grant on and saw no badges would otherwise reasonably think
    // it had failed.
    expect(await screen.findByText('Unpublished — badges nothing yet')).toBeInTheDocument();
  });
});
