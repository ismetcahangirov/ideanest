import { CalendarClock, CircleCheck, CircleDot, CircleSlash, Clock, Hourglass, Users } from 'lucide-react';
import Image from 'next/image';
import Link from 'next/link';
import type { ReactNode } from 'react';
import { MediaFrame, ProgressBar, StatBlock, Tag } from '@ideanest/ui/server';
import { PRELAUNCH_COVER_SIZES } from '../../lib/images/sizes';
import { canOptimise } from '../../lib/images/source';
import { formatMoney } from '../../lib/money';
import type { CampaignPage } from '../../lib/projects/publicPage';
import type { ProjectState } from '../../lib/projects/api';

/**
 * §4.4's header: the cover, the title, who made it, and how the funding stands.
 *
 * <h2>A Server Component, and that is the point of the file</h2>
 *
 * #119's complaint is that the campaign page assembled itself in the browser, so the
 * title, the summary, the creator's name and the funding figures were absent from the
 * HTML a crawler and a link unfurler are served. Nothing here is a client component and
 * nothing here fetches: every value is read from a projection the server already has.
 *
 * <h2>Colour</h2>
 *
 * **Lime is urgency and nothing else** (docs/ui-kit.md §2.4, §8.1). A campaign closing
 * within 48 hours gets a lime pill with near-black text; a campaign that funded gets
 * `--success`, on the progress bar and on the word beside it. The two are one token apart
 * and opposite in meaning — a backer who reads lime as "done" has been told the reverse.
 *
 * **Colour never carries meaning alone** (§9.2). Every badge here is a hue plus an icon
 * plus a word, and the completion figure is printed as text as well as drawn as a bar,
 * because a bar that only changes colour has said nothing to a screen reader.
 *
 * <h2>Motion</h2>
 *
 * None. `FadeUp` on the page heading is the one scroll-entry animation this surface gets
 * and it lives in the page, once (docs/motion-system.md §5). A header that animated its
 * funding figures would animate the number a backer is trying to read.
 */

/** Under two days left, which is what ui-kit §8.1 calls "closing within 48 hours". */
const URGENT_DAYS = 2;

interface StateBadge {
  readonly label: string;
  readonly icon: ReactNode;
  readonly variant: 'default' | 'success' | 'warning' | 'danger';
}

/**
 * The nine public states, as a word a reader recognises.
 *
 * Deliberately not the raw state name. `COLLECTING` and `FULFILLING` are internal facts
 * about where a campaign is in §6.1; what a visitor needs to know is that it funded and
 * the money is being taken. `CANCELED` is its own word rather than "unsuccessful", because
 * a campaign somebody stopped and a campaign that missed its goal are different things and
 * the backers of each were told different things.
 */
const BADGES: Partial<Record<ProjectState, StateBadge>> = {
  PRELAUNCH: { label: 'Coming soon', icon: <CalendarClock className="size-3.5" />, variant: 'default' },
  LIVE: { label: 'Live', icon: <CircleDot className="size-3.5" />, variant: 'default' },
  SUCCESSFUL: { label: 'Funded', icon: <CircleCheck className="size-3.5" />, variant: 'success' },
  COLLECTING: { label: 'Funded', icon: <CircleCheck className="size-3.5" />, variant: 'success' },
  LATE_PLEDGE: { label: 'Late pledges open', icon: <Hourglass className="size-3.5" />, variant: 'warning' },
  FULFILLING: { label: 'Fulfilling', icon: <Hourglass className="size-3.5" />, variant: 'success' },
  COMPLETED: { label: 'Completed', icon: <CircleCheck className="size-3.5" />, variant: 'success' },
  UNSUCCESSFUL: { label: 'Did not fund', icon: <CircleSlash className="size-3.5" />, variant: 'default' },
  CANCELED: { label: 'Cancelled', icon: <CircleSlash className="size-3.5" />, variant: 'default' },
};

function daysLeftLabel(days: number): string {
  if (days === 0) return 'Last day';
  return days === 1 ? '1 day left' : `${days} days left`;
}

export interface CampaignSummaryProps {
  readonly campaign: CampaignPage;
}

export function CampaignSummary({ campaign }: CampaignSummaryProps) {
  const badge = BADGES[campaign.state];
  const completion = campaign.completionPercent;
  const funded = completion !== null && completion.greaterThanOrEqualTo(100);

  /*
   * A COUNTDOWN ONLY WHILE THERE IS SOMETHING TO COUNT DOWN TO. `daysLeft` is floored at
   * zero, so a campaign that closed a fortnight ago reports the same number as one closing
   * tonight; "Last day" on the former is a lie, and lime would make it a loud one.
   */
  const showDays = campaign.daysLeft !== null && campaign.state === 'LIVE';
  const urgent = showDays && campaign.daysLeft !== null && campaign.daysLeft <= URGENT_DAYS;

  return (
    <header className="grid gap-8 lg:grid-cols-[minmax(0,1.6fr)_minmax(0,1fr)] lg:items-start">
      {/*
        The box is reserved whether or not there is a cover. `MediaFrame` sets the 16:9
        crop before anything loads, so the page's largest element does not change height
        when the photograph decodes — which is the layout shift the Core Web Vitals budget
        in CI is measured on.
      */}
      <MediaFrame ratio="16/9" radius="lg">
        {campaign.coverImage !== null && (
          <Image
            src={campaign.coverImage.url}
            alt=""
            fill
            sizes={PRELAUNCH_COVER_SIZES}
            /*
             * An address on a host the optimiser will not fetch is served as it is rather
             * than thrown over: `next/image` raises on a URL no remote pattern matches, and
             * a raised render in a Server Component takes the whole page down. One
             * creator's typo must not be able to do that.
             */
            unoptimized={!canOptimise(campaign.coverImage.url)}
            priority
            className="object-cover"
          />
        )}
      </MediaFrame>

      <div className="flex flex-col gap-5">
        <div className="flex flex-wrap items-center gap-2">
          {badge !== undefined && (
            <Tag variant={badge.variant} className="gap-1.5">
              <span aria-hidden="true" className="flex items-center">
                {badge.icon}
              </span>
              {badge.label}
            </Tag>
          )}

          {urgent && campaign.daysLeft !== null && (
            /*
              The one lime element on the page. A lime FILL with near-black text — lime text
              on a light surface measures 1.3:1 and is prohibited (ui-kit §9.1) — and
              `data-on-lime` so the focus ring flips for anything inside it (§9.3).
            */
            <span
              data-on-lime=""
              className="inline-flex h-[26px] items-center gap-1.5 rounded-sm bg-lime-500 px-2.5 text-xs font-medium text-on-lime"
            >
              <Clock aria-hidden="true" className="size-3" />
              {daysLeftLabel(campaign.daysLeft)}
            </span>
          )}

          {campaign.category !== null && (
            <Link
              href={`/discover?category=${encodeURIComponent(campaign.category.slug)}`}
              className="rounded-sm text-xs text-white/64 underline-offset-4 hover:text-white hover:underline"
            >
              {campaign.category.name}
            </Link>
          )}
        </div>

        <div className="flex flex-col gap-2">
          <h1 className="font-display text-3xl font-semibold tracking-[-0.03em] text-white sm:text-4xl">
            {campaign.title}
          </h1>
          {campaign.blurb !== null && <p className="text-base text-reading">{campaign.blurb}</p>}
        </div>

        <p className="text-sm text-white/64">
          by{' '}
          <Link
            href={`/discover?q=${encodeURIComponent(campaign.creator.name)}`}
            className="rounded-sm text-white underline-offset-4 hover:underline"
          >
            {campaign.creator.name}
          </Link>
        </p>

        {campaign.goal !== null && (
          <div className="flex flex-col gap-3">
            <ProgressBar
              value={completion === null ? 0 : completion.toNumber()}
              size="md"
              /*
               * The only place the percentage becomes a JavaScript number, and it is a
               * geometry rather than an amount: the width of a track in pixels. Everything a
               * reader is told is rendered from the Decimal below.
               */
              label={`Funding: ${completion === null ? 0 : completion.toFixed(0)} percent of the goal`}
            />

            <div className="flex flex-wrap items-baseline gap-x-6 gap-y-2">
              <StatBlock size="md" value={formatMoney(campaign.pledged)} label="pledged" />
              {completion !== null && (
                <StatBlock
                  size="md"
                  /*
                   * Text as well as a bar, ui-kit §8.2. `--success` once the goal is
                   * reached and never lime: reaching a goal is an achievement, and lime on
                   * this platform means "act now".
                   */
                  value={<span className={funded ? 'text-success' : undefined}>{completion.toFixed(0)}%</span>}
                  label={funded ? 'funded' : 'of goal'}
                />
              )}
              <StatBlock
                size="md"
                value={
                  <span className="inline-flex items-center gap-2">
                    <Users aria-hidden="true" className="size-5 text-white/48" />
                    {campaign.backersCount}
                  </span>
                }
                label={campaign.backersCount === 1 ? 'backer' : 'backers'}
              />
            </div>

            <p className="text-sm text-white/64">
              of {formatMoney(campaign.goal)} goal
              {showDays && campaign.daysLeft !== null && ` · ${daysLeftLabel(campaign.daysLeft)}`}
            </p>
          </div>
        )}
      </div>
    </header>
  );
}
