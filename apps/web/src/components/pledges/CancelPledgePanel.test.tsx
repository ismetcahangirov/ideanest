import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ApiError } from '../../lib/api/problem';
import { cancelPledge, type PledgeResponse } from '../../lib/pledges/api';
import { CancelPledgePanel } from './CancelPledgePanel';

/**
 * §4.5's PL-10 — the withdrawal, and its confirmation. Issue #287.
 *
 * WHAT THESE COVER:
 *
 *   - **the confirmation states what is released**, which is the whole reason it exists. A
 *     withdrawal refunds nothing, because nothing was collected (§9.7); what it does is give
 *     back the reward tier's place and each add-on's quantity, and on a limited tier somebody
 *     else will take them. A confirmation that said only "are you sure?" would be confirming
 *     the wrong thing.
 *   - **it does not claim to be stopping a payment.** A backer who believes they are cancelling
 *     a charge has misunderstood what they are doing, so the amount is named as something that
 *     has not been and will not be charged.
 *   - **nothing is sent until the second press**, and "keep my pledge" sends nothing at all.
 *   - focus moves to the consequence, so a keyboard reader is taken to the question rather than
 *     left on a button whose meaning has changed underneath them.
 */

vi.mock('../../lib/pledges/api', async () => {
  const actual = await vi.importActual<typeof import('../../lib/pledges/api')>(
    '../../lib/pledges/api',
  );
  return { ...actual, cancelPledge: vi.fn() };
});

const cancelMock = vi.mocked(cancelPledge);

const PLEDGE: PledgeResponse = {
  id: 'pledge-1',
  projectId: 'project-1',
  state: 'CONFIRMED',
  rewardTierId: 'tier-mug',
  addons: [{ rewardTierId: 'addon-track', quantity: 3 }],
  amounts: {
    base: { amount: '45.00', currency: 'AZN' },
    addons: { amount: '12.75', currency: 'AZN' },
    bonus: { amount: '0.00', currency: 'AZN' },
    shipping: { amount: '2.00', currency: 'AZN' },
    tax: { amount: '0.00', currency: 'AZN' },
    total: { amount: '59.75', currency: 'AZN' },
  },
  shippingCountry: 'AZ',
  isAnonymous: false,
  reservationExpiresAt: null,
  confirmedAt: '2026-01-01T00:00:00Z',
  canceledAt: null,
  paymentMethodId: null,
  cardVerified: false,
  latePledge: false,
  supplements: [],
};

function renderPanel(onCancelled = vi.fn()) {
  render(
    <CancelPledgePanel
      pledge={PLEDGE}
      campaignTitle="A folding bicycle"
      rewardTitle="Enamel mug"
      onCancelled={onCancelled}
    />,
  );
  return onCancelled;
}

async function openTheConfirmation(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getByRole('button', { name: 'Withdraw this pledge' }));
}

beforeEach(() => {
  vi.clearAllMocks();
  cancelMock.mockResolvedValue(undefined);
});

afterEach(cleanup);

describe('before the confirmation is opened', () => {
  it('sends nothing on the first press', async () => {
    const user = userEvent.setup();
    renderPanel();

    await openTheConfirmation(user);

    expect(cancelMock).not.toHaveBeenCalled();
  });
});

describe('the confirmation', () => {
  it('names the reward whose place goes back, and says somebody else can take it', async () => {
    const user = userEvent.setup();
    renderPanel();
    await openTheConfirmation(user);

    expect(screen.getByText('Withdrawing gives your place back to the campaign')).toBeInTheDocument();
    expect(screen.getByText(/Enamel mug/u)).toBeInTheDocument();
    expect(screen.getByText(/somebody else can take it/u)).toBeInTheDocument();
  });

  it('counts the add-on items that are released with it', async () => {
    const user = userEvent.setup();
    renderPanel();
    await openTheConfirmation(user);

    expect(screen.getByText('The 3 add-on items this pledge reserved go back too.')).toBeInTheDocument();
  });

  it('says the amount is not a charge being avoided', async () => {
    const user = userEvent.setup();
    renderPanel();
    await openTheConfirmation(user);

    /* `toHaveTextContent` on the line rather than `getByText` for the whole sentence: the
       amount is a `formatMoney` expression, so the sentence is three text nodes and the
       default matcher reads them one at a time. */
    expect(screen.getByText(/Nothing is refunded: /u)).toHaveTextContent(
      'the 59.75 AZN on this pledge has not been charged, and will not be',
    );
  });

  it('names the campaign somebody would have to back again', async () => {
    const user = userEvent.setup();
    renderPanel();
    await openTheConfirmation(user);

    expect(screen.getByText(/To back A folding bicycle again/u)).toBeInTheDocument();
  });

  it('is a named group and takes focus, so it is not missed by ear', async () => {
    const user = userEvent.setup();
    renderPanel();
    await openTheConfirmation(user);

    expect(screen.getByRole('group', { name: 'Confirm withdrawing this pledge' })).toBeInTheDocument();
    expect(screen.getByText('Withdrawing gives your place back to the campaign')).toHaveFocus();
  });

  it('backs out without sending anything', async () => {
    const user = userEvent.setup();
    renderPanel();
    await openTheConfirmation(user);

    await user.click(screen.getByRole('button', { name: 'Keep my pledge' }));

    expect(cancelMock).not.toHaveBeenCalled();
    expect(screen.queryByRole('group')).not.toBeInTheDocument();
  });
});

describe('withdrawing', () => {
  it('sends the withdrawal and tells the page to re-read the pledge', async () => {
    const user = userEvent.setup();
    const onCancelled = renderPanel();
    await openTheConfirmation(user);

    await user.click(screen.getByRole('button', { name: 'Yes, withdraw my pledge' }));

    expect(cancelMock).toHaveBeenCalledTimes(1);
    const [id, key] = cancelMock.mock.calls[0] ?? [];
    expect(id).toBe('pledge-1');
    // §10.3: an `Idempotency-Key` on every payment mutation, so a withdrawal whose response was
    // lost is replayed rather than sent again.
    expect(key).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-/iu);
    expect(onCancelled).toHaveBeenCalledTimes(1);
  });

  it('reuses one key across retries, because a retry is the same intent', async () => {
    const user = userEvent.setup();
    renderPanel();
    await openTheConfirmation(user);

    cancelMock.mockRejectedValueOnce(new TypeError('network'));
    await user.click(screen.getByRole('button', { name: 'Yes, withdraw my pledge' }));
    await screen.findByText('The service could not be reached');

    await user.click(screen.getByRole('button', { name: 'Yes, withdraw my pledge' }));

    const first = cancelMock.mock.calls[0]?.[1];
    const second = cancelMock.mock.calls[1]?.[1];
    expect(second).toBe(first);
  });

  it('says why a refusal happened rather than leaving the button apparently broken', async () => {
    const user = userEvent.setup();
    const onCancelled = renderPanel();
    await openTheConfirmation(user);

    cancelMock.mockRejectedValue(
      new ApiError(409, { code: 'PLEDGE_NOT_EDITABLE', title: 'Pledge can no longer be changed' }),
    );
    await user.click(screen.getByRole('button', { name: 'Yes, withdraw my pledge' }));

    expect(await screen.findByText('This pledge can no longer be changed')).toBeInTheDocument();
    expect(onCancelled).not.toHaveBeenCalled();
  });
});

describe('a pledge with no reward', () => {
  it('says so rather than naming a tier that is not there', async () => {
    const user = userEvent.setup();
    render(
      <CancelPledgePanel
        pledge={{ ...PLEDGE, rewardTierId: null, addons: [] }}
        campaignTitle="A folding bicycle"
        rewardTitle={null}
        onCancelled={vi.fn()}
      />,
    );
    await openTheConfirmation(user);

    expect(
      screen.getByText('This pledge holds no reward tier, so nothing is set aside for it.'),
    ).toBeInTheDocument();
    expect(screen.queryByText(/add-on item/u)).not.toBeInTheDocument();
  });
});
