import { getLocale, getTranslations } from 'next-intl/server';
import { type CheckoutCopy, checkoutCopyFrom } from './checkout-copy';
import {
  type CampaignActionsCopy,
  type CommentCopy,
  campaignActionsCopyFrom,
  commentCopyFrom,
} from './campaign-copy';
import { localeOrDefault, type Locale } from './locale';
import { type TrailCopy } from '../seo/structured-data/breadcrumb';
import { trailCopyFrom } from './trail-copy';
import {
  type FailureCopy,
  type FooterCopy,
  type ShellCopy,
  failureCopyFrom,
  footerCopyFrom,
  shellCopyFrom,
} from './shell-copy';

/**
 * The shell's copy, read from the request's catalogue — issue #324.
 *
 * <h2>Why this is a second file</h2>
 *
 * `shell-copy.ts` holds the types and the pure builders and imports nothing from
 * `next-intl/server`. That split is what lets a component test build the identical object out
 * of `messages/*.json` — asserting against the words the application will draw rather than
 * against words retyped into the test, which is a test that passes whatever the catalogue
 * says. Keeping the server call here means importing the shape never drags a server module
 * into a client bundle.
 */
export async function shellCopy(): Promise<ShellCopy> {
  return shellCopyFrom(await getTranslations('shell'));
}

export async function footerCopy(): Promise<FooterCopy> {
  return footerCopyFrom(await getTranslations('shell'));
}

export async function failureCopy(): Promise<FailureCopy> {
  return failureCopyFrom(await getTranslations('shell'));
}

/** The checkout's words. `checkout-copy.ts` explains why the whole of it is one prop. */
export async function checkoutCopy(): Promise<CheckoutCopy> {
  return checkoutCopyFrom(await getTranslations('checkout'));
}

/** The save, share and reminder controls. */
export async function campaignActionsCopy(): Promise<CampaignActionsCopy> {
  return campaignActionsCopyFrom(await getTranslations('campaign.actions'));
}

/** The composer and the two comment controls, which share one section. */
export async function commentCopy(): Promise<CommentCopy> {
  return commentCopyFrom(await getTranslations('campaign.comments'));
}

/**
 * The fixed steps of a `BreadcrumbList`, in the page's language — #123.
 *
 * Every route that emits structured data with a trail resolves this and hands it to its graph
 * builder. See `lib/i18n/trail-copy.ts` for why the markup is localised rather than left as
 * the English constants it was born with.
 */
export async function trailCopy(): Promise<TrailCopy> {
  return trailCopyFrom(await getTranslations('common.trail'));
}

/**
 * Everything a structured-data graph needs to name a page in its own language — #123.
 *
 * <p>The two travel together because every graph builder needs both and neither is useful
 * alone: the locale is what prefixes the URLs a crawler will follow, and the copy is what the
 * steps are called once it gets there. A route that resolved one and forgot the other would
 * emit a trail in Russian pointing at English pages, which is worse than either mistake on
 * its own.
 *
 * <p>`getLocale` rather than the route's `params`: `layout.tsx` calls `setRequestLocale` with
 * the segment, so this reads the value the router already resolved and leaves the render as
 * static as it found it. Reading a header or a cookie here would undo `i18n/routing.ts`.
 */
export async function graphContext(): Promise<{
  readonly locale: Locale;
  readonly trailCopy: TrailCopy;
}> {
  return { locale: localeOrDefault(await getLocale()), trailCopy: await trailCopy() };
}
