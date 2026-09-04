'use client';

import { useCallback } from 'react';
import { FolderOpen } from 'lucide-react';
import { EmptyState, InlineAlert, Pill, Skeleton, SkeletonGroup } from '@ideanest/ui';
import { Link } from '../../i18n/navigation';
import type { ProfileProjectCard } from '../../lib/profiles/api';
import { isPubliclyVisible, listMyProjects, myCampaignHref } from '../../lib/projects/mine';
import { useCursorList } from './useCursorList';

/**
 * `/account/campaigns` — every campaign this account has started.
 *
 * <h2>Why the screen exists</h2>
 *
 * <p>It did not, and nothing in the product replaced it. `POST /v1/projects` answers with an
 * identifier and every other project route takes one in its path, so a creator who closed the
 * tab held nothing; the account menu's only campaign-shaped row was "start a campaign", which
 * posts a new draft. A creator with an unfinished campaign therefore had two options: the URL,
 * if they had written it down, or a second empty draft.
 *
 * <h2>Rows, not the profile grid</h2>
 *
 * <p>`ProfileCampaignGrid` renders the same cards and is the wrong component here. It is
 * keyed on a slug, it is seeded by a server render, and every card on it links to a public
 * campaign page — which is an address a draft does not have. What a creator needs from this
 * list is the state and a way in, and both of those are things the profile grid deliberately
 * does not show.
 *
 * <h2>The state is a word, never a colour</h2>
 *
 * <p>docs/ui-kit.md §9.2 forbids colour from carrying meaning on its own, and this is the
 * case it is for: "submitted" and "changes requested" are a fortnight apart in what a creator
 * should do next, and a reader who cannot separate two greens would be told nothing. So the
 * state is spelled out, and the row has no status colour at all.
 *
 * <h2>Motion: none</h2>
 *
 * <p>docs/motion-system.md §8 forbids animating a list, and appended pages are the case it
 * names. The button changes its label while it waits; nothing moves.
 */

export interface MyCampaignsPanelCopy {
  readonly emptyTitle: string;
  readonly emptyBody: string;
  readonly startCampaign: string;
  readonly loadFailed: string;
  readonly loadingList: string;
  readonly loadMore: string;
  readonly loadingMore: string;
  readonly draftHint: string;
  /**
   * §6.1's sixteen, by wire name.
   *
   * <p>Borrowed from `admin.screens.campaignDirectory`, for the reason `CampaignPreviewCopy`
   * gives: sixteen state names under a second key is a second set of translations for
   * `CHANGES_REQUESTED` that nothing keeps in step with the first.
   */
  readonly states: Readonly<Record<string, string>>;
}

export interface MyCampaignsPanelProps {
  readonly copy: MyCampaignsPanelCopy;
}

export function MyCampaignsPanel({ copy }: MyCampaignsPanelProps) {
  const { status, items, hasMore, loadingMore, error, loadMore } = useCursorList<ProfileProjectCard>(
    useCallback((cursor, signal) => listMyProjects(cursor, signal), []),
  );

  /*
   * Nothing, not a sign-in prompt. `AccountArea` owns the wall for every screen under
   * `/account`, and a second one inside the panel would render below the first.
   */
  if (status === 'signed-out') return null;

  if (status === 'loading') {
    return (
      <SkeletonGroup label={copy.loadingList} className="flex flex-col gap-3">
        {[0, 1, 2].map((row) => (
          <Skeleton key={row} height="4.5rem" />
        ))}
      </SkeletonGroup>
    );
  }

  if (status === 'failed') {
    return (
      <InlineAlert variant="danger" title={copy.loadFailed}>
        <p>{error}</p>
      </InlineAlert>
    );
  }

  if (items.length === 0) {
    return (
      <EmptyState
        icon={<FolderOpen aria-hidden="true" className="size-6" />}
        title={copy.emptyTitle}
        description={copy.emptyBody}
        action={
          <Link href="/projects/new">
            <Pill type="button">{copy.startCampaign}</Pill>
          </Link>
        }
      />
    );
  }

  return (
    <div className="flex flex-col gap-6">
      <ul className="flex list-none flex-col gap-3">
        {items.map((campaign) => (
          <li key={campaign.id}>
            <Link
              href={myCampaignHref(campaign)}
              className="flex flex-col gap-2 rounded-lg border border-white/8 bg-surface-2 p-4 transition-colors duration-150 ease-in-out hover:bg-surface-3 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[var(--lime-500)] sm:flex-row sm:items-center sm:justify-between sm:gap-4"
            >
              <span className="flex min-w-0 flex-col gap-1">
                {/*
                  `wrap-anywhere` and not `truncate`: a working title is frequently one
                  unbroken string, and a creator looking for the draft they left needs to
                  read the whole of it rather than its first thirty characters.
                */}
                <span className="wrap-anywhere text-[15px] font-medium text-white">
                  {campaign.title}
                </span>
                {!isPubliclyVisible(campaign) && (
                  <span className="text-xs text-white/40">{copy.draftHint}</span>
                )}
              </span>

              <span className="shrink-0 rounded-full border border-white/8 px-3 py-1 text-xs text-white/64">
                {copy.states[campaign.state] ?? campaign.state}
              </span>
            </Link>
          </li>
        ))}
      </ul>

      {hasMore && (
        <div>
          <Pill type="button" onClick={loadMore} aria-disabled={loadingMore}>
            {loadingMore ? copy.loadingMore : copy.loadMore}
          </Pill>
        </div>
      )}

      {/*
        A page that failed to load must not destroy the pages already read. The alert sits
        under the list rather than replacing it, which is what `useCursorList` distinguishes
        `failed` from an error on a later page for.
      */}
      {error !== null && status === 'ready' && (
        <InlineAlert variant="danger" title={copy.loadFailed}>
          <p>{error}</p>
        </InlineAlert>
      )}
    </div>
  );
}
