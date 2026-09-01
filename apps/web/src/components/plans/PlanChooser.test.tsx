import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ApiError } from '../../lib/api/problem';
import type { HeldSubscription, Plan } from '../../lib/plans/api';
import { cancelSubscription, readMySubscription, subscribeToPlan } from '../../lib/plans/api';
import { PlanChooser } from './PlanChooser';
import { pricingCopyFrom } from '../../lib/i18n/plans-copy';
import { translatorFor } from '../../test-copy';

/*
 * The copy is built from `messages/en.json` with the builder the route calls, rather than
 * typed out here. `src/test-copy.ts` explains why: a suite that retyped the sentences would
 * still be green with the catalogue empty, which is what `catalogue.test.ts` exists to catch.
 */
const COPY = pricingCopyFrom(translatorFor('pricing'));

vi.mock('../../lib/plans/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../lib/plans/api')>();
  return {
    ...actual,
    readMySubscription: vi.fn(),
    subscribeToPlan: vi.fn(),
    cancelSubscription: vi.fn(),
  };
});

/*
 * The chooser navigates now: a creator sent here by a refused submission is taken back to
 * their campaign the moment the plan they chose entitles them.
 *
 * Spread first so the real module's other exports survive -- `i18n/navigation.tsx` reads
 * `redirect` and `permanentRedirect` at import time, and a factory that replaced the module
 * wholesale left those undefined.
 */
const push = vi.fn();

vi.mock('next/navigation', async (importOriginal) => ({
  ...(await importOriginal<typeof import('next/navigation')>()),
  useRouter: () => ({ push, replace: vi.fn(), back: vi.fn() }),
}));

const readMock = vi.mocked(readMySubscription);
const subscribeMock = vi.mocked(subscribeToPlan);
const cancelMock = vi.mocked(cancelSubscription);

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

const PRO: Plan = {
  ...STARTER,
  id: '11111111-0000-4000-8000-000000000003',
  code: 'PRO',
  name: 'Pro',
  description: null,
  price: '149.00',
  maxActiveCampaigns: null,
  goalCeiling: null,
  sortOrder: 30,
};

const PLANS: readonly Plan[] = [STARTER, PRO];

function held(overrides: Partial<HeldSubscription> = {}): HeldSubscription {
  return {
    id: '22222222-0000-4000-8000-000000000001',
    state: 'ACTIVE',
    entitled: true,
    plan: STARTER,
    price: '19.00',
    currency: 'AZN',
    billingPeriod: 'MONTHLY',
    startedAt: '2026-08-01T09:00:00Z',
    currentPeriodEnd: '2026-09-01T09:00:00Z',
    cancelAtPeriodEnd: false,
    createdAt: '2026-08-01T09:00:00Z',
    ...overrides,
  };
}

function draw(props: Partial<React.ComponentProps<typeof PlanChooser>> = {}) {
  return render(<PlanChooser plans={PLANS} copy={COPY} locale="en" {...props} />);
}

beforeEach(() => {
  // The router is shared by every test in this file; a redirect one of them provoked must not
  // be counted against the next.
  push.mockClear();
});

describe('the plan chooser', () => {
  it('renders the prices before it knows anything about the reader', () => {
    // Never resolves: the catalogue is a prop and must not wait on a fetch.
    readMock.mockReturnValue(new Promise(() => {}));

    draw();

    expect(screen.getByText('Starter')).toBeInTheDocument();
    expect(screen.getByText('Pro')).toBeInTheDocument();
  });

  it('says a limitless plan has no limit rather than showing a number', async () => {
    readMock.mockResolvedValue({ subscription: null });

    draw();

    await waitFor(() => expect(readMock).toHaveBeenCalled());
    expect(screen.getByText(COPY.limits.campaignsUnlimited)).toBeInTheDocument();
    expect(screen.getByText(COPY.limits.goalUnlimited)).toBeInTheDocument();
  });

  it('offers the prices to somebody with no session rather than refusing the page', async () => {
    // A 401 here is a visitor deciding whether to bring their campaign here, which is what
    // this page is for -- not a failure.
    readMock.mockRejectedValue(new ApiError(401, null, 'not signed in'));

    draw();

    expect(await screen.findByText(COPY.signedOut)).toBeInTheDocument();
    expect(screen.getByText('Starter')).toBeInTheDocument();
    expect(screen.queryByText(COPY.errors.generic)).not.toBeInTheDocument();
  });

  it('explains why the reader is here when a refused submission sent them', async () => {
    readMock.mockResolvedValue({ subscription: null });

    draw({ fromProjectId: 'abc-123' });

    expect(await screen.findByText(COPY.fromSubmit.title)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: COPY.fromSubmit.back })).toHaveAttribute(
      'href',
      '/en/projects/abc-123/edit/review',
    );
  });

  it('takes the creator back to their campaign the moment the plan entitles them', async () => {
    readMock.mockResolvedValue({ subscription: null });
    subscribeMock.mockResolvedValue({ subscription: held() });

    draw({ fromProjectId: 'abc-123' });

    await waitFor(() => expect(readMock).toHaveBeenCalled());
    await userEvent.click(screen.getByRole('button', { name: /Choose this plan: Starter/ }));

    // The page has finished its business with them: they came to be allowed to submit, and
    // they now are. Leaving them on a price list to find their own way back is the failure
    // `?from=submit&project=` exists to prevent.
    // `stringContaining` because `useRouter` prefixes the reader's language, the same way the
    // review panel's own redirect does.
    await waitFor(() =>
      expect(push).toHaveBeenCalledWith(
        expect.stringContaining('/projects/abc-123/edit/review'),
      ),
    );
  });

  it('leaves a creator on the price list when the plan is chosen and not yet paid for', async () => {
    readMock.mockResolvedValue({ subscription: null });
    subscribeMock.mockResolvedValue({
      subscription: held({ state: 'PENDING_PAYMENT', entitled: false, currentPeriodEnd: null }),
    });

    draw({ fromProjectId: 'abc-123' });

    await waitFor(() => expect(readMock).toHaveBeenCalled());
    await userEvent.click(screen.getByRole('button', { name: /Choose this plan: Starter/ }));

    // Sending them back would send them to a campaign that refuses them again, which is a
    // worse answer than the page explaining what is still needed.
    expect(await screen.findByText(COPY.held.pendingBody)).toBeInTheDocument();
    expect(push).not.toHaveBeenCalled();
  });

  it('offers the way back once the plan is active, rather than repeating why they came', async () => {
    readMock.mockResolvedValue({ subscription: held() });

    draw({ fromProjectId: 'abc-123' });

    expect(await screen.findByText(COPY.fromSubmit.ready)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: COPY.fromSubmit.resume })).toHaveAttribute(
      'href',
      '/en/projects/abc-123/edit/review',
    );
    expect(screen.queryByText(COPY.fromSubmit.title)).not.toBeInTheDocument();
  });

  it('says nothing about a campaign when the reader arrived on their own', async () => {
    readMock.mockResolvedValue({ subscription: null });

    draw();

    await waitFor(() => expect(readMock).toHaveBeenCalled());
    // Somebody who found this page from the navigation is not owed an explanation of why
    // they are looking at it.
    expect(screen.queryByText(COPY.fromSubmit.title)).not.toBeInTheDocument();
  });

  it('does not congratulate somebody on a payment the platform has not seen', async () => {
    readMock.mockResolvedValue({ subscription: null });
    subscribeMock.mockResolvedValue({
      subscription: held({ state: 'PENDING_PAYMENT', entitled: false, currentPeriodEnd: null }),
    });

    draw();

    await waitFor(() => expect(readMock).toHaveBeenCalled());
    await userEvent.click(screen.getByRole('button', { name: /Choose this plan: Starter/ }));

    expect(await screen.findByText(COPY.held.pendingBody)).toBeInTheDocument();
    expect(subscribeMock).toHaveBeenCalledWith(STARTER.id);
  });

  it('says when a plan runs to, and offers to stop it renewing', async () => {
    readMock.mockResolvedValue({ subscription: held() });

    draw();

    expect(await screen.findByRole('button', { name: COPY.held.cancel })).toBeInTheDocument();
  });

  it('does not offer to cancel a subscription that is already ending', async () => {
    readMock.mockResolvedValue({ subscription: held({ cancelAtPeriodEnd: true }) });

    draw();

    await waitFor(() => expect(readMock).toHaveBeenCalled());
    expect(screen.queryByRole('button', { name: COPY.held.cancel })).not.toBeInTheDocument();
  });

  it('keeps the entitlement on screen after cancelling, because the period was paid for', async () => {
    readMock.mockResolvedValue({ subscription: held() });
    cancelMock.mockResolvedValue({ subscription: held({ cancelAtPeriodEnd: true }) });

    draw();

    await userEvent.click(await screen.findByRole('button', { name: COPY.held.cancel }));

    await waitFor(() => expect(cancelMock).toHaveBeenCalled());
    // Still entitled: taking the month back on the click would be charging for something and
    // then withdrawing it.
    expect(screen.queryByText(COPY.held.pendingBody)).not.toBeInTheDocument();
  });

  it('names the refusal it was given rather than one message for everything', async () => {
    readMock.mockResolvedValue({ subscription: null });
    subscribeMock.mockRejectedValue(
      new ApiError(409, { code: 'PLAN_NOT_ON_SALE' }, 'no longer offered'),
    );

    draw();

    await waitFor(() => expect(readMock).toHaveBeenCalled());
    await userEvent.click(screen.getByRole('button', { name: /Choose this plan: Starter/ }));

    expect(await screen.findByText(COPY.errors.notOnSale)).toBeInTheDocument();
  });
});
