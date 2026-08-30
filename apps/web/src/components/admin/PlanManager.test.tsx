import { describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ApiError } from '../../lib/api/problem';
import type { ConsoleSubscription, Plan } from '../../lib/admin/plans';
import {
  activateSubscription,
  addPlan,
  changePlan,
  readPlanCatalogue,
  readSubscriptions,
} from '../../lib/admin/plans';
import { PlanManager } from './PlanManager';
import { translatorFor } from '../../test-copy';
import { consoleChromeCopyFrom } from '../../lib/i18n/admin/common-copy';
import { planManagerCopyFrom } from '../../lib/i18n/admin/money-copy';

/*
 * The copy is built from `messages/en.json` with the same builders the route calls, rather
 * than typed out here — `src/test-copy.ts` has the argument.
 */
const COPY = planManagerCopyFrom(
  translatorFor('admin'),
  consoleChromeCopyFrom(translatorFor('admin'), translatorFor('common')),
);

vi.mock('../../lib/admin/plans', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../lib/admin/plans')>()),
  readPlanCatalogue: vi.fn(),
  readSubscriptions: vi.fn(),
  addPlan: vi.fn(),
  changePlan: vi.fn(),
  activateSubscription: vi.fn(),
  cancelSubscriptionAsStaff: vi.fn(),
}));

const catalogueMock = vi.mocked(readPlanCatalogue);
const subscriptionsMock = vi.mocked(readSubscriptions);
const addPlanMock = vi.mocked(addPlan);
const changePlanMock = vi.mocked(changePlan);
const activateMock = vi.mocked(activateSubscription);

const STARTER: Plan = {
  id: '11111111-0000-4000-8000-000000000001',
  code: 'STARTER',
  name: 'Starter',
  description: 'For a first project.',
  price: '19.00',
  currency: 'AZN',
  billingPeriod: 'MONTHLY',
  maxActiveCampaigns: 1,
  goalCeiling: '10000.00',
  listed: true,
  sortOrder: 10,
  updatedAt: '2026-08-01T09:00:00Z',
};

const RETIRED: Plan = { ...STARTER, id: '11111111-0000-4000-8000-000000000009', code: 'OLD', name: 'Old', listed: false };

const WAITING: ConsoleSubscription = {
  id: '22222222-0000-4000-8000-000000000001',
  accountId: '33333333-0000-4000-8000-000000000001',
  state: 'PENDING_PAYMENT',
  entitled: false,
  planCode: 'STARTER',
  planName: 'Starter',
  price: '19.00',
  currency: 'AZN',
  billingPeriod: 'MONTHLY',
  startedAt: null,
  currentPeriodEnd: null,
  cancelAtPeriodEnd: false,
  activatedBy: null,
  note: null,
  createdAt: '2026-08-20T09:00:00Z',
};

function ready(plans: readonly Plan[] = [STARTER], subscriptions: readonly ConsoleSubscription[] = []) {
  catalogueMock.mockResolvedValue({ plans });
  subscriptionsMock.mockResolvedValue({ subscriptions });
}

describe('the plan manager', () => {
  it('says out loud that an edit reaches everybody on the plan', async () => {
    ready();

    render(<PlanManager copy={COPY} />);

    // Somebody arriving from the fee editor next door arrives expecting nothing to be
    // editable at all. Without this notice they lower a limit believing it applies only to
    // new customers.
    expect(await screen.findByText(COPY.noticeBody)).toBeInTheDocument();
  });

  it('lists retired plans too, because their subscribers are still on them', async () => {
    ready([STARTER, RETIRED]);

    render(<PlanManager copy={COPY} />);

    expect((await screen.findAllByText(/Old/))[0]).toBeInTheDocument();
    expect(screen.getByText(COPY.retired)).toBeInTheDocument();
  });

  it('offers no way to delete a plan, only to take it off sale', async () => {
    ready();

    render(<PlanManager copy={COPY} />);
    await screen.findAllByText(/Starter/);

    expect(screen.queryByRole('button', { name: /delete/i })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Take off sale/ })).toBeInTheDocument();
  });

  it('takes a plan off sale rather than removing it', async () => {
    ready();
    changePlanMock.mockResolvedValue({ ...STARTER, listed: false });

    render(<PlanManager copy={COPY} />);
    await userEvent.click(await screen.findByRole('button', { name: /Take off sale/ }));

    await waitFor(() =>
      expect(changePlanMock).toHaveBeenCalledWith({ planId: STARTER.id, listed: false }),
    );
  });

  it('sends an empty limit as no limit rather than as zero', async () => {
    ready();
    addPlanMock.mockResolvedValue(STARTER);

    render(<PlanManager copy={COPY} />);
    await screen.findAllByText(/Starter/);

    await userEvent.type(screen.getByLabelText(COPY.codeLabel), 'growth');
    await userEvent.type(screen.getByLabelText(COPY.nameLabel), 'Growth');
    await userEvent.clear(screen.getByLabelText(COPY.maxActiveLabel));
    await userEvent.click(screen.getByRole('button', { name: COPY.addPlan }));

    await waitFor(() => expect(addPlanMock).toHaveBeenCalled());
    const sent = addPlanMock.mock.calls[0]?.[0];
    expect(sent?.maxActiveCampaigns).toBeNull();
    expect(sent?.goalCeiling).toBeNull();
    // Upper-cased on the way out, so "growth" and "GROWTH" are not two plans.
    expect(sent?.code).toBe('GROWTH');
  });

  it('records a payment against a subscription that is waiting for one', async () => {
    ready([STARTER], [WAITING]);
    activateMock.mockResolvedValue({ ...WAITING, state: 'ACTIVE', entitled: true });

    render(<PlanManager copy={COPY} />);
    await screen.findAllByText(/Starter/);

    await userEvent.type(screen.getByLabelText(COPY.noteLabel), 'transfer 44');
    await userEvent.click(screen.getByRole('button', { name: COPY.recordPayment }));

    await waitFor(() => expect(activateMock).toHaveBeenCalledWith(WAITING.id, 'transfer 44'));
  });

  it('offers no payment control against a subscription that is already active', async () => {
    ready([STARTER], [{ ...WAITING, state: 'ACTIVE', entitled: true, currentPeriodEnd: '2026-09-20T09:00:00Z' }]);

    render(<PlanManager copy={COPY} />);
    await screen.findAllByText(/Starter/);

    expect(screen.queryByRole('button', { name: COPY.recordPayment })).not.toBeInTheDocument();
  });

  it('refuses honestly when the reader lacks the capability', async () => {
    catalogueMock.mockRejectedValue(new ApiError(403, { code: 'INSUFFICIENT_STAFF_CAPABILITY' }));
    subscriptionsMock.mockRejectedValue(new ApiError(403, { code: 'INSUFFICIENT_STAFF_CAPABILITY' }));

    render(<PlanManager copy={COPY} />);

    // Named rather than hidden: a member of staff who cannot see the screen has no way to
    // find out that it exists — `lib/admin/navigation.ts` has the argument.
    expect(await screen.findByText(COPY.refusals.forbiddenTitle)).toBeInTheDocument();
  });
});
