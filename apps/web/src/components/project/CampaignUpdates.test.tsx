import { afterEach, describe, expect, it } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import type { CampaignUpdate, CampaignUpdatePage } from '../../lib/community/updates';
import { CampaignUpdates } from './CampaignUpdates';

/**
 * §4.4's Updates tab — #284.
 *
 * WHAT THESE COVER:
 *
 *   - **the number on screen is the number the service allocated.** §4.9 allocates it once,
 *     at insert, and never recomputes it, because "update 7 said the moulds were late" is a
 *     thing somebody says to support six months later. A component that numbered by position
 *     would renumber every earlier update the first time one was withheld — silently.
 *   - **a backers-only update says so in a word, not in a colour** (docs/ui-kit.md §9.2), and
 *     it is rendered rather than filtered: the service decided this caller may see it.
 *   - **"could not be loaded" and "has posted nothing" are different sentences.** Printing the
 *     second over a restarting service is a claim about the creator that happens to be false.
 *   - **the body is text, never markup.** An update body is plain text a creator typed and it
 *     reaches this component from a public endpoint.
 *   - **older updates are a link**, so the archive is reachable without JavaScript and by a
 *     crawler.
 */

function update(overrides: Partial<CampaignUpdate> = {}): CampaignUpdate {
  return {
    number: 7,
    title: 'The moulds were late',
    body: 'The factory slipped by two weeks.',
    visibility: 'PUBLIC',
    publishedAt: '2026-08-01T09:00:00Z',
    authorId: 'u1',
    ...overrides,
  };
}

function page(overrides: Partial<CampaignUpdatePage> = {}): CampaignUpdatePage {
  return { updates: [update()], nextCursor: null, ...overrides };
}

afterEach(cleanup);

describe('the updates tab', () => {
  it('prints the number the service allocated rather than the position in the list', () => {
    render(
      <CampaignUpdates
        page={page({ updates: [update({ number: 7 }), update({ number: 2, title: 'Two' })] })}
        olderHref={null}
        paged={false}
      />,
    );

    expect(screen.getByText('Update 7')).toBeInTheDocument();
    expect(screen.getByText('Update 2')).toBeInTheDocument();
    expect(screen.queryByText('Update 1')).not.toBeInTheDocument();
  });

  it('gives every update a heading and a machine-readable publication date', () => {
    const { container } = render(<CampaignUpdates page={page()} olderHref={null} paged={false} />);

    expect(
      screen.getByRole('heading', { level: 3, name: 'The moulds were late' }),
    ).toBeInTheDocument();
    expect(container.querySelector('time')).toHaveAttribute('datetime', '2026-08-01T09:00:00Z');
  });

  it('renders a backers-only update the service sent, and marks it in words', () => {
    render(
      <CampaignUpdates
        page={page({ updates: [update({ visibility: 'BACKERS_ONLY' })] })}
        olderHref={null}
        paged={false}
      />,
    );

    expect(screen.getByText('The moulds were late')).toBeInTheDocument();
    expect(screen.getByText('Backers only')).toBeInTheDocument();
  });

  it('renders the body as text, never as markup', () => {
    render(
      <CampaignUpdates
        page={page({ updates: [update({ body: '<img src=x onerror="alert(1)">' })] })}
        olderHref={null}
        paged={false}
      />,
    );

    expect(screen.getByText('<img src=x onerror="alert(1)">')).toBeInTheDocument();
  });

  it('keeps an update that is only a title', () => {
    render(
      <CampaignUpdates
        page={page({ updates: [update({ body: '' })] })}
        olderHref={null}
        paged={false}
      />,
    );

    expect(screen.getByRole('heading', { level: 3, name: 'The moulds were late' })).toBeInTheDocument();
  });

  it('says the campaign has posted nothing only when the campaign has posted nothing', () => {
    render(<CampaignUpdates page={page({ updates: [] })} olderHref={null} paged={false} />);

    expect(screen.getByText(/has not posted an update yet/u)).toBeInTheDocument();
  });

  it('blames the service, not the creator, when the read was refused', () => {
    render(<CampaignUpdates page={null} olderHref={null} paged={false} />);

    expect(screen.getByText(/could not be loaded/u)).toBeInTheDocument();
    expect(screen.queryByText(/has not posted an update yet/u)).not.toBeInTheDocument();
  });

  it('says "no older updates" rather than "none at all" past the first page', () => {
    render(<CampaignUpdates page={page({ updates: [] })} olderHref={null} paged />);

    expect(screen.getByText('There are no older updates.')).toBeInTheDocument();
  });

  it('offers the older page as a link, so it is reachable without JavaScript', () => {
    render(
      <CampaignUpdates
        page={page({ nextCursor: 3 })}
        olderHref="/projects/ayan/coffee-table-book?tab=updates&from=3"
        paged={false}
      />,
    );

    expect(screen.getByRole('link', { name: 'Older updates' })).toHaveAttribute(
      'href',
      '/projects/ayan/coffee-table-book?tab=updates&from=3',
    );
  });

  /**
   * §4.9 lists C-05 — comments on an update — among the things that are not built, and the
   * comment endpoints address a campaign rather than an update. A control here would file a
   * reply about update 7 under the campaign at large, where nobody reading update 7 would
   * find it.
   */
  it('offers no comment control on an update', () => {
    render(<CampaignUpdates page={page()} olderHref={null} paged={false} />);

    expect(screen.queryByRole('button', { name: /comment|reply/iu })).not.toBeInTheDocument();
  });
});
