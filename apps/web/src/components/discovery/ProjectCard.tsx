import Decimal from 'decimal.js';
import Image from 'next/image';
import { Link } from '../../i18n/navigation';
import {
  CalendarClock,
  CircleCheck,
  CircleDot,
  CircleSlash,
  Clock,
  Hourglass,
  Users,
} from 'lucide-react';
import type { ReactNode } from 'react';
import { MediaFrame, ProgressBar, Tag } from '@ideanest/ui/server';
import { DISCOVERY_CARD_SIZES } from '../../lib/images/sizes';
import { canOptimise } from '../../lib/images/source';
import { formatMoney } from '../../lib/money';
import type { ProjectCard as ProjectCardData } from '../../lib/discovery/api';
import type { DiscoveryStatus } from '../../lib/discovery/vocabulary';

/**
 * D-05's project card: image, title, creator, completion, days left, badge.
 *
 * MONEY IS NEVER A NUMBER. `pledged`, `goal`, and `completionPercent` all arrive
 * as strings and stay that way (CLAUDE.md §3, docs/architecture.md §10.3). The
 * percentage is read with `decimal.js`; the only place a JavaScript number
 * appears is the WIDTH of the progress track, which is a geometry in pixels and
 * not an amount anybody is owed.
 *
 * LIME IS THE URGENCY BADGE, NOT THE CARD. docs/ui-kit.md §8.1 maps "closing
 * within 48 hours" to a lime card, and §1.1 says exactly one card in a row is
 * lime because lime is a state rather than decoration. In a feed those two
 * collide: sorted by ending-soon, forty cards qualify, and forty lime cards is
 * the decoration §1.1 forbids — the signal means nothing when everything
 * carries it. So the surface stays `--surface-2` and the urgency is a lime PILL
 * on it, which is still "a lime surface with near-black text" (§2.3) and still
 * scarce inside a single card. The decision is recorded in docs/ui-kit.md §8.2.
 *
 * FUNDED IS `--success`, NEVER LIME. `ProgressBar` switches at 100% on its own;
 * the figure beside it is text, because a bar that only changes colour has said
 * nothing to a reader with a colour-vision deficiency and nothing at all to a
 * screen reader (§9.2). Every badge here is colour PLUS an icon PLUS a word for
 * the same reason.
 *
 * THE COVER RESERVES ITS BOX BEFORE IT LOADS. `MediaFrame` carries the 16:9
 * crop, so the height of every card is decided at first paint and the grid does
 * not reflow as twenty-four photographs decode. `next/image` supplies the AVIF
 * and WebP variants and the `sizes` string says how wide the card really is at
 * each breakpoint, which is what stops a 440-pixel box downloading a
 * 3840-pixel photograph. Both are derived rather than typed here —
 * `lib/images/sizes.ts` reads them off this grid's own Tailwind classes.
 *
 * `@ideanest/ui/server`, NOT THE ROOT BARREL. All three of these render identically on a
 * server and in a browser, and this card is now used from Server Components — the home page,
 * the category landing pages and the search results — as well as from the client-rendered
 * feed. The barrel reaches `createContext`, and importing it into a Server Component is a
 * build error naming a component the page never used; `packages/ui/src/server.ts` explains
 * the split, and says the lean entry is the one to use even from a client component.
 *
 * NO ENTRY ANIMATION ON THE CARD ITSELF. Discovery's motion budget is "skeleton
 * to content crossfade only" (docs/motion-system.md §5) and §8 forbids animation
 * in long lists outright — fifty animated cards in a feed produce visible jank.
 * The one `FadeUp` on this surface is on the page heading, in `DiscoveryView`.
 */

/** Under two days left, which is what §8.1 calls "closing within 48 hours". */
const URGENT_DAYS = 2;

interface BadgeSpec {
  readonly label: string;
  readonly icon: ReactNode;
  readonly variant: 'default' | 'success' | 'warning' | 'danger';
}

/**
 * The five status words, each with an icon and a hue.
 *
 * `successful` is `--success` and `late_pledge` is `--warning`: a late-pledge
 * window is a clock running, which is what warning means here, and reaching the
 * goal is the achievement `--success` exists for. Neither is lime — a backer who
 * reads lime as "done" has been told the opposite of the truth (§2.4).
 */
const BADGES: Record<DiscoveryStatus, BadgeSpec> = {
  upcoming: { label: 'Upcoming', icon: <CalendarClock className="size-3" />, variant: 'default' },
  live: { label: 'Live', icon: <CircleDot className="size-3" />, variant: 'default' },
  late_pledge: { label: 'Late pledge', icon: <Hourglass className="size-3" />, variant: 'warning' },
  successful: { label: 'Successful', icon: <CircleCheck className="size-3" />, variant: 'success' },
  unsuccessful: {
    label: 'Unsuccessful',
    icon: <CircleSlash className="size-3" />,
    variant: 'default',
  },
};

/**
 * The completion figure, read as a decimal and never as a number.
 *
 * Absent for a campaign with no goal — every `PRELAUNCH` row. A percentage of
 * nothing is undefined rather than zero, and rendering it as "0%" would tell a
 * reader a campaign had raised none of a goal it has not set.
 */
function completionOf(card: ProjectCardData): Decimal | null {
  const raw = card.completionPercent;
  if (raw == null || raw === '') return null;

  try {
    return new Decimal(raw);
  } catch {
    // A malformed percentage is a card without a progress bar, not a crash.
    return null;
  }
}

function daysLeftLabel(days: number): string {
  if (days === 0) return 'Last day';
  return days === 1 ? '1 day left' : `${days} days left`;
}

export interface ProjectCardProps {
  card: ProjectCardData;
  /**
   * Loads the cover eagerly and asks the browser to fetch it first.
   *
   * True for the cards above the fold and nothing else. One of them is the
   * largest contentful paint on `/discover`, and a lazy image cannot be that
   * until layout has run — which is a measurable delay on the metric
   * docs/motion-system.md §8 puts under two seconds. Setting it on the whole
   * feed would put twenty-four covers into the same priority queue as the
   * document and make every one of them later.
   */
  priority?: boolean;
}

export function ProjectCard({ card, priority = false }: ProjectCardProps) {
  const completion = completionOf(card);
  const badge = card.badge == null ? null : BADGES[card.badge];
  const days = card.daysLeft ?? null;

  /*
   * A COUNTDOWN ONLY WHILE THERE IS SOMETHING TO COUNT DOWN TO. `daysLeft` is
   * zero once the deadline has passed, so a campaign that closed a fortnight
   * ago reports the same number as one closing tonight. "Last day" on the
   * former is a lie, and lime would make it a loud one — so zero counts only
   * while the campaign is still `LIVE`.
   */
  const showDays = days !== null && (days > 0 || card.state === 'LIVE');
  const urgent = showDays && card.state === 'LIVE' && days !== null && days <= URGENT_DAYS;

  /*
   * The public campaign page mirrors the API's own addressing —
   * `GET /v1/projects/{creatorSlug}/{projectSlug}` (§10.2) — which is why the
   * card carries `creatorSlug` at all. #119 built the page this answers.
   */
  const href = `/projects/${encodeURIComponent(card.creatorSlug)}/${encodeURIComponent(card.slug)}`;

  return (
    <article className="group relative flex flex-col overflow-hidden rounded-lg border border-white/8 bg-surface-2 transition-colors duration-300 ease-in-out hover:bg-surface-3">
      {/*
        Full-bleed at the top with radius on the upper corners only — the
        article clips, so the frame itself is square (docs/ui-kit.md §8.2).

        THE BOX IS RESERVED WHETHER OR NOT THERE IS A COVER. `MediaFrame` sets
        the 16:9 crop before anything loads, so a card with a cover and a card
        without one are the same height and the grid below never moves. A
        campaign with no cover gets that reserved surface rather than a broken
        image or a stock graphic that says nothing.

        THE CROP TOKEN, NOT THE INTRINSIC SIZE. Every cover is cut to 16:9 here
        whatever shape it was uploaded in, so the reservation is the crop. The
        recorded width and height belong to the picture, not to this box.

        ALT IS EMPTY BY DECISION. The title is the next element and it is the
        link; a description of the cover would be a second announcement of the
        same campaign, and there is nothing this component could invent that
        the creator did not write.
      */}
      <MediaFrame ratio="16/9">
        {card.image != null && (
          <Image
            src={card.image.url}
            alt=""
            fill
            sizes={DISCOVERY_CARD_SIZES}
            /*
             * An address on a host the optimiser will not fetch is served as
             * it is rather than thrown over. `next/image` raises on a URL no
             * remote pattern matches, and a raised render in a server
             * component blanks the whole feed — one creator's typo must not be
             * able to do that. See `lib/images/source.ts`.
             */
            unoptimized={!canOptimise(card.image.url)}
            priority={priority}
            className="object-cover"
          />
        )}
      </MediaFrame>

      <div className="flex flex-1 flex-col gap-3 p-5">
        <div className="flex flex-wrap items-center gap-2">
          {badge !== null && (
            <Tag variant={badge.variant} className="gap-1.5">
              <span aria-hidden="true" className="flex items-center">
                {badge.icon}
              </span>
              {badge.label}
            </Tag>
          )}

          {urgent && days !== null && (
            /*
              The one lime element on the card, and the only thing on this
              surface that means "hurry". A lime FILL with near-black text —
              lime text on a light surface measures 1.3:1 and is prohibited
              (§9.1). The clock icon and the words carry the meaning; the colour
              only raises it.
            */
            <span
              data-on-lime=""
              className="inline-flex h-[26px] items-center gap-1.5 rounded-sm bg-lime-500 px-2.5 text-xs font-medium text-on-lime"
            >
              <Clock aria-hidden="true" className="size-3" />
              {daysLeftLabel(days)}
            </span>
          )}
        </div>

        <h3 className="text-lg font-medium tracking-[-0.02em] text-white">
          {/*
            The whole card is reachable through this one link rather than
            through three — a stretched anchor keeps the pointer target the size
            of the card while leaving exactly one tab stop and one announcement
            per campaign.
          */}
          <Link href={href} className="after:absolute after:inset-0 after:content-['']">
            {card.title}
          </Link>
        </h3>

        <p className="text-sm text-white/64">
          by <span className="text-white/80">{card.creator.name}</span>
        </p>

        {completion !== null ? (
          <div className="mt-auto flex flex-col gap-2 pt-2">
            <ProgressBar
              value={
                /*
                 * A width, not an amount. The figure a reader acts on is the
                 * text below, which comes from the decimal; this number decides
                 * how many pixels of track are filled and nothing else.
                 */
                completion.toNumber()
              }
              label={`${completion.toFixed(0)} percent of the goal`}
            />
            <div className="flex flex-wrap items-baseline justify-between gap-x-3 gap-y-1 text-sm">
              <span className="font-medium text-white tabular-nums">
                {formatMoney(card.pledged)}
              </span>
              <span className="text-white/64 tabular-nums">
                {completion.toFixed(0)}% funded
              </span>
            </div>
            {card.goal != null && (
              <p className="text-xs text-white/40 tabular-nums">
                of {formatMoney(card.goal)} goal
              </p>
            )}
          </div>
        ) : (
          <p className="mt-auto pt-2 text-sm text-white/40">Not open for pledges yet</p>
        )}

        <div className="flex items-center gap-4 text-xs text-white/40">
          <span className="inline-flex items-center gap-1.5">
            <Users aria-hidden="true" className="size-3.5" />
            <span className="tabular-nums">
              {card.backersCount === 1 ? '1 backer' : `${card.backersCount} backers`}
            </span>
          </span>

          {/*
            Days left as plain text whenever it is not already the lime pill, so
            the figure is present on every card that has a deadline rather than
            only on the urgent ones.
          */}
          {!urgent && showDays && days !== null && (
            <span className="inline-flex items-center gap-1.5">
              <Clock aria-hidden="true" className="size-3.5" />
              <span className="tabular-nums">{daysLeftLabel(days)}</span>
            </span>
          )}
        </div>
      </div>
    </article>
  );
}
