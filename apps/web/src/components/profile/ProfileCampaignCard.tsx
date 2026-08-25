import Decimal from 'decimal.js';
import Image from 'next/image';
import { Link } from '../../i18n/navigation';
import { CalendarClock, CircleCheck, CircleDot, CircleSlash, Hourglass, Package, Users } from 'lucide-react';
import type { ReactNode } from 'react';
import { MediaFrame, ProgressBar, Tag } from '@ideanest/ui/server';
import { canOptimise } from '../../lib/images/source';
import { sizesFor } from '../../lib/images/sizes';
import { formatMoney } from '../../lib/money';
import { profileCampaignHref, type ProfileProjectCard } from '../../lib/profiles/api';

/**
 * One campaign on a profile — §4.2 P-04 and P-05, issue #274.
 *
 * <h2>READ THIS BEFORE ADDING A NUMBER TO THE BACKED LIST</h2>
 *
 * **§4.2's P-04 is "Backed projects archive (no amounts)", and it means no amounts at all.**
 * Not the amount this person pledged, and not the amount the campaign has raised. The
 * `funding="withheld"` branch below renders neither, and the service does not send them, so
 * there is nothing here to un-hide.
 *
 * The rule is easy to mistake for an oversight, which is why it is written down twice. It is
 * not about the size of somebody's pledge being secret from them — they can read it on their
 * own pledge page. It is that a public, permanent, paginated list of the campaigns a named
 * person funded, **with the money beside each one**, is a financial profile of them that they
 * never agreed to publish. The list of campaigns is a statement of interest; the list of
 * campaigns with figures is a statement of means. §4.5's PL-12 makes the same distinction
 * from the other end by keeping anonymous pledges off the list entirely.
 *
 * A reader who wants a campaign's funding total is one click away from the campaign, where it
 * is published by the creator about their own campaign. That is where it belongs.
 *
 * <h2>The created list does show money, and that is not inconsistent</h2>
 *
 * A creator's own campaigns publish their goal and their total on every surface the platform
 * has — the discovery card, the campaign page, the search result. Withholding them on the
 * creator's own profile would hide a public figure from the one page that exists to
 * summarise it.
 *
 * <h2>Money is never a JavaScript number</h2>
 *
 * `goal` and `pledged` arrive as decimal strings and are read with `decimal.js` (CLAUDE.md
 * §3). The only number in this file is the width of the progress track, which is a geometry
 * in pixels rather than an amount anybody is owed — the same line `components/discovery/ProjectCard`
 * draws.
 *
 * <h2>Colour never carries the meaning</h2>
 *
 * Every status is a hue **and** an icon **and** a word (docs/ui-kit.md §9.2), and the
 * completion figure is printed as text beside the bar rather than left to the fill. There is
 * no lime anywhere on this card: nothing on a profile is urgent, and a profile is not a feed
 * with one card in it that is about to close (§8.1, §8.2).
 *
 * <h2>Motion: none</h2>
 *
 * docs/motion-system.md §8 forbids animation in long lists outright, and a profile's created
 * list is unbounded downward in exactly the way a feed is. The cover's box is reserved by
 * `MediaFrame` before the bytes arrive, so a grid of twelve does not reflow as they decode.
 */

/**
 * The nine publicly visible states of §6.1, as words, each with an icon.
 *
 * A `Record<string, …>` rather than a `Record<ProjectState, …>` because the wire type is
 * widened (`lib/profiles/api.ts` says why): a state a newer service returns renders as
 * itself rather than as an empty tag or a crash. The nine that are here are the nine a
 * public list can contain.
 */
const STATE: Record<string, { readonly label: string; readonly icon: ReactNode; readonly variant: 'default' | 'success' | 'warning' }> = {
  PRELAUNCH: { label: 'Pre-launch', icon: <CalendarClock className="size-3" />, variant: 'default' },
  SCHEDULED: { label: 'Scheduled', icon: <CalendarClock className="size-3" />, variant: 'default' },
  LIVE: { label: 'Live', icon: <CircleDot className="size-3" />, variant: 'default' },
  SUCCESSFUL: { label: 'Funded', icon: <CircleCheck className="size-3" />, variant: 'success' },
  UNSUCCESSFUL: { label: 'Not funded', icon: <CircleSlash className="size-3" />, variant: 'default' },
  COLLECTING: { label: 'Collecting', icon: <Hourglass className="size-3" />, variant: 'warning' },
  LATE_PLEDGE: { label: 'Late pledges', icon: <Hourglass className="size-3" />, variant: 'warning' },
  FULFILLING: { label: 'Fulfilling', icon: <Package className="size-3" />, variant: 'default' },
  COMPLETED: { label: 'Completed', icon: <CircleCheck className="size-3" />, variant: 'success' },
};

/**
 * How wide a card really is on this grid, so a 380-pixel box does not download a
 * 3840-pixel photograph.
 *
 * Read off the grid in `ProfileCampaignGrid`: one column with `px-5`, two from `sm` with a
 * 16px gap and `px-6`, three from `lg` inside a column that stops growing at 1120 + 48 pixels
 * — so `(1120 - 48 - 32) / 3` is 347. `DISCOVERY_CARD_SIZES` describes a different grid, and
 * reusing it would be a `sizes` string that describes a layout this page does not have.
 */
const PROFILE_CARD_SIZES = sizesFor([
  { minWidth: 1168, size: '347px' },
  { minWidth: 640, size: 'calc(50vw - 32px)' },
  { size: 'calc(100vw - 40px)' },
]);

/**
 * The completion figure, as a decimal.
 *
 * Absent when there is no goal, when there is no pledged total, and when the goal is zero.
 * A percentage of nothing is undefined rather than zero, and printing "0% funded" over a
 * campaign that has not opened tells a reader it failed to raise a goal it never set.
 */
function completionOf(goal: string, pledged: string): Decimal | null {
  try {
    const target = new Decimal(goal);
    if (target.lessThanOrEqualTo(0)) return null;
    return new Decimal(pledged).dividedBy(target).times(100);
  } catch {
    // A malformed figure is a card without a progress bar, not a crash.
    return null;
  }
}

export interface ProfileCampaignCardProps {
  readonly card: ProfileProjectCard;
  /**
   * Whether the funding figures may be printed.
   *
   * **`'withheld'` on the backed list, always.** It is a required prop rather than a default
   * so that a new caller has to decide, in writing, which list it is rendering — a default of
   * `'shown'` would make the P-04 breach the thing that happens when somebody forgets.
   */
  readonly funding: 'shown' | 'withheld';
  /** Loads the cover eagerly. True only for the cards above the fold. */
  readonly priority?: boolean;
}

export function ProfileCampaignCard({ card, funding, priority = false }: ProfileCampaignCardProps) {
  const badge = STATE[card.state];

  const goal = card.goal ?? null;
  const pledged = card.pledged ?? null;
  const completion =
    funding === 'shown' && goal !== null && pledged !== null
      ? completionOf(goal.amount, pledged.amount)
      : null;

  return (
    <article className="group relative flex flex-col overflow-hidden rounded-lg border border-white/8 bg-surface-2 transition-colors duration-300 ease-in-out hover:bg-surface-3">
      {/*
        The box is reserved whether or not there is a cover, so a campaign with a photograph
        and one without are the same height and the grid below never moves (docs/ui-kit.md
        §7.16). `alt` is empty by decision: the title is the next element and it is the link.
      */}
      <MediaFrame ratio="16/9">
        {card.coverImage !== null && (
          <Image
            src={card.coverImage.url}
            alt=""
            fill
            sizes={PROFILE_CARD_SIZES}
            /* A host the optimiser will not fetch is served as it is rather than thrown
               over — one creator's typo must not blank the whole grid. */
            unoptimized={!canOptimise(card.coverImage.url)}
            priority={priority}
            className="object-cover"
          />
        )}
      </MediaFrame>

      <div className="flex flex-1 flex-col gap-3 p-5">
        {badge !== undefined ? (
          <div>
            <Tag variant={badge.variant} className="gap-1.5">
              <span aria-hidden="true" className="flex items-center">
                {badge.icon}
              </span>
              {badge.label}
            </Tag>
          </div>
        ) : (
          <div>
            <Tag>{card.state}</Tag>
          </div>
        )}

        <h3 className="text-lg font-medium tracking-[-0.02em] text-white">
          {/* One link for the whole card: one tab stop and one announcement per campaign,
              with the pointer target still the size of the card. */}
          <Link
            href={profileCampaignHref(card)}
            className="after:absolute after:inset-0 after:content-['']"
          >
            {card.title}
          </Link>
        </h3>

        {card.blurb !== null && card.blurb !== '' && (
          <p className="line-clamp-2 text-sm text-white/64">{card.blurb}</p>
        )}

        {funding === 'shown' && completion !== null && pledged !== null ? (
          <div className="mt-auto flex flex-col gap-2 pt-2">
            <ProgressBar
              /* A width, not an amount. The figure a reader acts on is the text below it,
                 which comes from the decimal. */
              value={completion.toNumber()}
              label={`${completion.toFixed(0)} percent of the goal`}
            />
            <div className="flex flex-wrap items-baseline justify-between gap-x-3 gap-y-1 text-sm">
              <span className="font-medium tabular-nums text-white">{formatMoney(pledged)}</span>
              <span className="tabular-nums text-white/64">{completion.toFixed(0)}% funded</span>
            </div>
            {goal !== null && (
              <p className="text-xs tabular-nums text-white/40">of {formatMoney(goal)} goal</p>
            )}
          </div>
        ) : (
          <div className="mt-auto pt-2" />
        )}

        {/*
          THE BACKER COUNT IS NOT AN AMOUNT. P-04 withholds money; how many people backed a
          campaign is published on the campaign's own page for everybody, is not a figure
          about the person whose profile this is, and is what makes the row legible as a
          campaign rather than as a title on its own.
        */}
        <div className="flex items-center gap-4 text-xs text-white/40">
          <span className="inline-flex items-center gap-1.5">
            <Users aria-hidden="true" className="size-3.5" />
            <span className="tabular-nums">
              {card.backersCount === 1 ? '1 backer' : `${card.backersCount} backers`}
            </span>
          </span>
        </div>
      </div>
    </article>
  );
}
