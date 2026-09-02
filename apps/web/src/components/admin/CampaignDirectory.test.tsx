import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ApiError } from '../../lib/api/problem';
import type { CampaignDirectoryPage, DirectoryCampaign } from '../../lib/admin/campaigns';
import { listCampaigns } from '../../lib/admin/campaigns';
import { CampaignDirectory } from './CampaignDirectory';
import { translatorFor } from '../../test-copy';
import { consoleChromeCopyFrom } from '../../lib/i18n/admin/common-copy';
import { campaignDirectoryCopyFrom } from '../../lib/i18n/admin/content-copy';

/*
 * Copy built from `messages/en.json` through the same builder the route calls, for the
 * reason `src/test-copy.ts` gives: a suite that retyped the sentences would stay green with
 * the catalogue empty.
 */
const COPY = campaignDirectoryCopyFrom(
  translatorFor('admin'),
  consoleChromeCopyFrom(translatorFor('admin'), translatorFor('common')),
);

vi.mock('../../lib/admin/campaigns', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../lib/admin/campaigns')>();
  return { ...actual, listCampaigns: vi.fn() };
});

const listCampaignsMock = vi.mocked(listCampaigns);

const DRAFT: DirectoryCampaign = {
  projectId: 'a1b2c3d4-0000-4000-8000-000000000001',
  title: 'Xari Bulbul Ceramics',
  slug: 'xari-bulbul-ceramics',
  state: 'DRAFT',
  createdAt: '2026-08-24T09:00:00.000Z',
  launchedAt: null,
  deadline: null,
  goal: { amount: '5000.00', currency: 'AZN' },
  pledged: { amount: '0.00', currency: 'AZN' },
  backersCount: 0,
  creatorId: 'creator-0001',
  creatorName: 'Aysel Səfərova',
  creatorSlug: 'aysel-studio',
};

const LIVE: DirectoryCampaign = {
  ...DRAFT,
  projectId: 'a1b2c3d4-0000-4000-8000-000000000002',
  title: 'Tumar Notebooks',
  slug: 'tumar-notebooks',
  state: 'LIVE',
  launchedAt: '2026-08-01T09:00:00.000Z',
  deadline: '2026-09-30T09:00:00.000Z',
  pledged: { amount: '1250.00', currency: 'AZN' },
  backersCount: 34,
};

function page(overrides: Partial<CampaignDirectoryPage> = {}): CampaignDirectoryPage {
  return { state: null, campaigns: [DRAFT], nextCursor: null, ...overrides };
}

beforeEach(() => {
  vi.clearAllMocks();
  listCampaignsMock.mockResolvedValue(page());
});

describe('the campaign directory', () => {
  it('lists a draft nobody has submitted, which is the gap it exists to close', async () => {
    render(<CampaignDirectory copy={COPY} />);

    // The whole point. Every other route into a campaign starts from something the
    // campaign did, so a draft was reachable from no screen in the console.
    expect(await screen.findByText('Xari Bulbul Ceramics')).toBeInTheDocument();
    expect(screen.getByText('Aysel Səfərova')).toBeInTheDocument();
    // Inside the row: the same word is on the filter beside it, which is the point of the
    // filter rather than a collision worth renaming either of them for.
    expect(within(screen.getByRole('listitem')).getByText(COPY.state.DRAFT)).toBeInTheDocument();
    // No filter was asked for, and none was sent.
    expect(listCampaignsMock).toHaveBeenCalledWith(expect.objectContaining({ state: null }));
  });

  it('says what a campaign has raised and from how many people', async () => {
    listCampaignsMock.mockResolvedValue(page({ campaigns: [LIVE] }));

    render(<CampaignDirectory copy={COPY} />);

    // Through `formatMoney`, like every other amount on the platform — #403. This screen
     // and the submission queue printed `${amount} ${currency}` and were the only two that
     // did, so `15000.00 AZN` sat on a console whose public site says `12,000.00 AZN`.
    expect(await screen.findByText('1,250.00 AZN')).toBeInTheDocument();
    expect(screen.getByText('34')).toBeInTheDocument();
  });

  it('says a draft has no goal rather than drawing a blank', async () => {
    listCampaignsMock.mockResolvedValue(page({ campaigns: [{ ...DRAFT, goal: null }] }));

    render(<CampaignDirectory copy={COPY} />);

    expect(await screen.findByText(COPY.noGoal)).toBeInTheDocument();
  });

  it('draws no launch date for a campaign that has never launched', async () => {
    render(<CampaignDirectory copy={COPY} />);

    await screen.findByText('Xari Bulbul Ceramics');
    // An empty value under a label reads as a missing figure. This campaign has not
    // launched, which is an absent event rather than an unknown one.
    expect(screen.queryByText(COPY.launchedLabel)).not.toBeInTheDocument();
    expect(screen.queryByText(COPY.deadlineLabel)).not.toBeInTheDocument();
  });

  it('names a campaign whose creator has been anonymised rather than hiding it', async () => {
    listCampaignsMock.mockResolvedValue(
      page({ campaigns: [{ ...DRAFT, creatorName: null, creatorSlug: null }] }),
    );

    render(<CampaignDirectory copy={COPY} />);

    // §17.4 removes the person and leaves the campaign. A placeholder name would tell a
    // member of staff there is somebody to write to.
    expect(await screen.findByText(COPY.creatorGone)).toBeInTheDocument();
    expect(screen.getByText('Xari Bulbul Ceramics')).toBeInTheDocument();
  });

  it('narrows by state through the service rather than in the browser', async () => {
    render(<CampaignDirectory copy={COPY} />);

    await screen.findByText('Xari Bulbul Ceramics');
    listCampaignsMock.mockResolvedValue(page({ state: 'LIVE', campaigns: [LIVE] }));

    await userEvent.click(screen.getByRole('button', { name: COPY.state.LIVE }));

    // A count drawn from a page narrowed in the browser is a claim about the page that
    // reads as a claim about the platform.
    await waitFor(() =>
      expect(listCampaignsMock).toHaveBeenCalledWith(expect.objectContaining({ state: 'LIVE' })),
    );
    expect(await screen.findByText('Tumar Notebooks')).toBeInTheDocument();
  });

  it('distinguishes a platform with no campaigns from a filter that matches none', async () => {
    listCampaignsMock.mockResolvedValue(page({ campaigns: [] }));

    render(<CampaignDirectory copy={COPY} />);

    expect(await screen.findByText(COPY.emptyTitle)).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: COPY.state.SUSPENDED }));

    expect(await screen.findByText(COPY.filteredTitle)).toBeInTheDocument();
  });

  it('continues from the cursor rather than repeating the first page', async () => {
    listCampaignsMock.mockResolvedValue(page({ nextCursor: DRAFT.projectId }));

    render(<CampaignDirectory copy={COPY} />);

    const more = await screen.findByRole('button', { name: COPY.loadMore });
    listCampaignsMock.mockResolvedValue(page({ campaigns: [LIVE], nextCursor: null }));
    await userEvent.click(more);

    await waitFor(() =>
      expect(listCampaignsMock).toHaveBeenCalledWith(
        expect.objectContaining({ after: DRAFT.projectId }),
      ),
    );
    expect(await screen.findByText('Tumar Notebooks')).toBeInTheDocument();
    expect(screen.getByText('Xari Bulbul Ceramics')).toBeInTheDocument();
  });

  it('says which capability a refusal wanted rather than showing an empty list', async () => {
    listCampaignsMock.mockRejectedValue(
      new ApiError(403, { code: 'CAPABILITY_REQUIRED' }, 'not permitted'),
    );

    render(<CampaignDirectory copy={COPY} />);

    // An empty directory and a refused one look identical, and one of them is a bug
    // somebody would go looking for in the database.
    expect(await screen.findByText(COPY.refusals.forbiddenTitle)).toBeInTheDocument();
  });

  it('offers to try again when the service could not be reached', async () => {
    listCampaignsMock.mockRejectedValue(new TypeError('fetch failed'));

    render(<CampaignDirectory copy={COPY} />);

    expect(await screen.findByRole('button', { name: COPY.tryAgain })).toBeInTheDocument();
  });
});
