import { describe, expect, it } from 'vitest';
import { translatorFor } from '../../test-copy';
import { consoleChromeCopyFrom } from '../i18n/admin/common-copy';
import { moneyCopyFrom } from '../i18n/admin/money-copy';
import { ApiError } from '../api/problem';
import { trailQuery } from './audit';
import {
  bodyOf,
  collectionTitle,
  isPublished,
  isWindowOpen,
  type AdminCollection,
} from './curation';
import { accountLabel, ledgerQuery } from './ledger';
import { paymentLogQuery, statusVariant } from './payments';
import { consoleMessageFor, shortId, statusFor } from './refusals';

/*
 * The copy is built from `messages/en.json` with the same functions the screens call, rather
 * than typed out here — `src/test-copy.ts` explains why at length: a test that retyped the
 * sentences would pass with the catalogue empty, which is exactly the defect these assertions
 * are for.
 */
const CHROME = consoleChromeCopyFrom(translatorFor('admin'), translatorFor('common'));
const MONEY = moneyCopyFrom(translatorFor('admin'));

/**
 * The console's read modules — issues #300 to #314.
 *
 * <p>Everything here is pure, and it is the half of these screens that can be wrong without
 * anything looking wrong: a query string that drops a filter answers with more than was asked
 * for, and on the ledger and the payment log "more than was asked for" is somebody else's
 * money on the screen.
 */

const NOW = new Date('2026-08-24T12:00:00.000Z');

function collection(overrides: Partial<AdminCollection> = {}): AdminCollection {
  return {
    id: 'c-1',
    slug: 'autumn-picks',
    kind: 'staff_selection',
    copy: { az: { title: 'Payız seçimi' } },
    grantsBadge: false,
    sortOrder: 10,
    ...overrides,
  };
}

describe('the audit trail query', () => {
  it('sends only the filters that were set', () => {
    expect(trailQuery({ limit: 25 })).toBe('limit=25');
    expect(trailQuery({ limit: 25, entityType: 'project' })).toBe('limit=25&entityType=project');
  });

  it('treats an empty filter as absent rather than as a value', () => {
    // An `entityType=` in the query is a filter for the empty kind, which matches nothing —
    // and an audit screen that answered "nothing has ever happened" because a text field was
    // cleared is the wrong answer to give about this table.
    expect(trailQuery({ limit: 25, entityType: '', actorId: '' })).toBe('limit=25');
  });

  it('carries the cursor', () => {
    expect(trailQuery({ limit: 10, after: 'row-9' })).toBe('limit=10&after=row-9');
  });
});

describe('the payment log query', () => {
  it('sends the two filters the service has an index for', () => {
    expect(paymentLogQuery({ limit: 25, projectId: 'p-1' })).toBe('limit=25&projectId=p-1');
    expect(paymentLogQuery({ limit: 25, pledgeId: 'g-1' })).toBe('limit=25&pledgeId=g-1');
  });

  it('sends both when both are given, and lets the service decide', () => {
    // The service resolves the pair down to the pledge and echoes what it applied. Deciding
    // that here as well would be a second implementation of one rule, and the day the
    // service changes its mind the client would keep the old answer.
    expect(paymentLogQuery({ limit: 25, pledgeId: 'g-1', projectId: 'p-1' })).toBe(
      'limit=25&pledgeId=g-1&projectId=p-1',
    );
  });
});

describe('a transaction status', () => {
  it('never draws a successful collection in lime', () => {
    // Lime means "act now" (docs/ui-kit.md). A collection that worked is the opposite of
    // something to act on, and on a financial screen green and lime both read as approval.
    expect(statusVariant('SUCCEEDED')).toBe('success');
    expect(statusVariant('FAILED')).toBe('danger');
    expect(statusVariant('PENDING')).toBe('warning');
  });
});

describe('the ledger query', () => {
  it('combines the account and the campaign, which the index supports', () => {
    expect(ledgerQuery({ limit: 20, account: 'escrow', projectId: 'p-1' })).toBe(
      'limit=20&account=escrow&projectId=p-1',
    );
  });

  it('sends a cursor of zero, which is a position and not an absence', () => {
    // `after=0` is a real place in a bigint sequence. A truthiness check here would drop it
    // and quietly restart the list from the newest posting.
    expect(ledgerQuery({ limit: 20, after: 0 })).toBe('limit=20&after=0');
  });
});

describe('a ledger account name', () => {
  it('reads §7.2 five accounts in words', () => {
    expect(accountLabel('escrow', MONEY)).toBe('Escrow');
    expect(accountLabel('platform_fee', MONEY)).toBe('Platform fee');
  });

  it('says which creator, without pretending to know who they are', () => {
    expect(accountLabel('creator:0191f2ab-1234-7000-8000-000000000001', MONEY)).toBe(
      'Creator 0191f2ab',
    );
  });

  it('shows an unknown account rather than hiding it', () => {
    // The column has a check constraint, so a value outside the six is something that should
    // not exist — which makes it the one row on the screen worth seeing.
    expect(accountLabel('something_else', MONEY)).toBe('something_else');
  });
});

describe('a collection', () => {
  it('falls back through the locales and then to its handle', () => {
    expect(collectionTitle(collection())).toBe('Payız seçimi');
    expect(collectionTitle(collection({ copy: { en: { title: 'Autumn picks' } } }))).toBe(
      'Autumn picks',
    );
    // A heading that renders empty is worse than one that renders a handle — the rule
    // `Taxonomy.resolveName` follows on the service side.
    expect(collectionTitle(collection({ copy: {} }))).toBe('autumn-picks');
  });

  it('is published exactly when the service says when', () => {
    expect(isPublished(collection())).toBe(false);
    expect(isPublished(collection({ publishedAt: '2026-08-01T00:00:00Z' }))).toBe(true);
  });

  it('treats a missing window bound as open rather than as closed', () => {
    expect(isWindowOpen(collection(), NOW)).toBe(true);
    expect(isWindowOpen(collection({ closesAt: '2026-08-20T00:00:00Z' }), NOW)).toBe(false);
    expect(isWindowOpen(collection({ opensAt: '2026-09-01T00:00:00Z' }), NOW)).toBe(false);
    expect(
      isWindowOpen(
        collection({ opensAt: '2026-08-01T00:00:00Z', closesAt: '2026-09-01T00:00:00Z' }),
        NOW,
      ),
    ).toBe(true);
  });

  it('rebuilds the whole body, because PUT replaces the whole description', () => {
    // The safety rail in front of the endpoint. A body assembled from the one field a screen
    // meant to change would clear the other six — two of which decide whether the list is an
    // open call and whether being in it badges a campaign.
    const body = bodyOf(
      collection({
        grantsBadge: true,
        opensAt: '2026-08-01T00:00:00Z',
        image: { url: 'https://example.test/cover.avif' },
      }),
    );

    expect(body).toEqual({
      kind: 'staff_selection',
      copy: { az: { title: 'Payız seçimi' } },
      cover: { url: 'https://example.test/cover.avif' },
      opensAt: '2026-08-01T00:00:00Z',
      closesAt: null,
      grantsBadge: true,
      sortOrder: 10,
    });
  });
});

describe('a console refusal', () => {
  it('tells a signed-out reader from one who is not staff', () => {
    // One is fixed by signing in again and the other cannot be fixed by the person reading
    // it. Collapsing them would send somebody whose token expired looking for a moderator.
    expect(statusFor(new ApiError(401, null, 'no session'))).toBe('signed-out');
    expect(statusFor(new ApiError(403, null, 'not staff'))).toBe('forbidden');
    expect(statusFor(new TypeError('offline'))).toBe('failed');
  });

  it('names what the reader was trying to read', () => {
    expect(
      consoleMessageFor(new ApiError(403, null, 'no'), 'the ledger', CHROME.refusals),
    ).toContain('the ledger');
  });

  it('branches on the code and not on the prose', () => {
    const problem = { code: 'UNKNOWN_LEDGER_ACCOUNT', detail: "'platform_fees' is not one of them." };
    expect(consoleMessageFor(new ApiError(400, problem, 'no'), 'the ledger', CHROME.refusals)).toBe(
      "'platform_fees' is not one of them.",
    );
  });

  it('says the service could not be reached when it could not', () => {
    expect(
      consoleMessageFor(new TypeError('offline'), 'the ledger', CHROME.refusals),
    ).toContain('could not be reached');
  });
});

describe('a shortened identifier', () => {
  it('is the eight characters git settled on, so the console agrees with the queue', () => {
    expect(shortId('0191f2ab-1234-7000-8000-000000000001')).toBe('0191f2ab');
  });
});
