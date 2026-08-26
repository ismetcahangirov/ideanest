import { capitalised, dateTimeFormat, relativeTimeFormat, UNDATED } from '../i18n/formats';
import type { Locale } from '../i18n/locale';
import { formatMoney, type Money } from '../money';
import type {
  DeliveryMode,
  InboxNotification,
  NotificationCategory,
  NotificationChannel,
  NotificationType,
} from './api';

/**
 * What one notification says, and where opening it goes — #88.
 *
 * <h2>The copy lives here, not in the component</h2>
 *
 * A row is a sentence, and the sentence depends on the type and on what the rendering
 * document happens to carry. Keeping that in a pure function is what lets it be tested
 * against every type without mounting anything, which matters because there are
 * twenty-two of them and thirteen have no producer yet — a template that renders a hole is
 * discovered here rather than by whoever eventually receives the first one.
 *
 * The wording deliberately echoes `messages.properties`. A notification a person reads in
 * the inbox and then again in a digest email should not be two different sentences about
 * the same thing.
 *
 * <h2>`params` is a string, and it is parsed exactly once</h2>
 *
 * The service emits the `jsonb` column verbatim rather than re-serialising it, so what
 * arrives is text. Every read below goes through {@link readParams}, which never throws:
 * a document that cannot be parsed produces a row with the campaign missing rather than an
 * inbox that fails to render. Money is read as the API's `{amount, currency}` object and
 * formatted by `lib/money`, never by `Number()`.
 */

/** One notification, as a reader sees it. */
export interface NotificationView {
  /** The whole message, in one line. */
  readonly headline: string;
  /** The campaign it is about, when the document names one — #249. */
  readonly campaign: string | null;
  /** Where opening it goes, or null when the platform has no page for it yet. */
  readonly href: string | null;
}

/** The rendering document, or an empty one. */
export function readParams(params: string | undefined): Record<string, unknown> {
  if (params === undefined || params.trim() === '') return {};

  try {
    const parsed: unknown = JSON.parse(params);
    // `notifications_params_is_an_object` already keeps the column an object, so this is
    // the second lock. An array would otherwise index by number below and read nothing.
    return typeof parsed === 'object' && parsed !== null && !Array.isArray(parsed)
      ? (parsed as Record<string, unknown>)
      : {};
  } catch {
    return {};
  }
}

function textOf(params: Record<string, unknown>, key: string): string | null {
  const value = params[key];
  return typeof value === 'string' && value.trim() !== '' ? value : null;
}

/**
 * An amount from the document, formatted, or null.
 *
 * The shape is checked rather than assumed: `formatMoney` splits `amount` on a full stop,
 * so a document whose amount arrived as a JSON number would render something plausible and
 * wrong. §10.3 says an amount crosses the API as a string; a document that disagrees is one
 * this function declines to read.
 */
function moneyOf(params: Record<string, unknown>, key: string): string | null {
  const value = params[key];
  if (typeof value !== 'object' || value === null) return null;

  const money = value as Partial<Money>;
  if (typeof money.amount !== 'string' || typeof money.currency !== 'string') return null;

  return formatMoney({ amount: money.amount, currency: money.currency });
}

/**
 * The campaign this notification is about: what it is called, and its page.
 *
 * Both come from the document, which has carried them since #249. A row written before
 * that has neither, and this answers nulls — which is why every sentence below still reads
 * correctly with `campaign` absent.
 *
 * **The link needs both slugs.** §10.2's campaign page is
 * `/projects/{creatorSlug}/{projectSlug}`; a path built from the identifier matches no
 * route, so half a pair is no link rather than a shorter one.
 */
export function campaignOf(params: Record<string, unknown>): {
  readonly title: string | null;
  readonly href: string | null;
} {
  const creatorSlug = textOf(params, 'creatorSlug');
  const projectSlug = textOf(params, 'projectSlug');

  return {
    title: textOf(params, 'projectTitle'),
    href:
      creatorSlug !== null && projectSlug !== null
        ? `/projects/${encodeURIComponent(creatorSlug)}/${encodeURIComponent(projectSlug)}`
        : null,
  };
}

/**
 * A fact the sentence needs, or the phrase that stands in for it.
 *
 * A missing amount is possible on any row — a malformed document, a producer that changed
 * its payload — and "your pledge of  is confirmed" is worse than a vaguer sentence. So
 * every use below has a fallback and none of them is a blank.
 */
function amountOr(params: Record<string, unknown>, key: string, fallback: string): string {
  return moneyOf(params, key) ?? fallback;
}

/**
 * What this notification says.
 *
 * The switch is exhaustive over `NotificationType` and has no `default`, so a type added to
 * the contract fails to compile here until somebody has decided what the inbox says about
 * it — the same property `EmailComposer`'s switch has on the service side, and for the same
 * reason: the alternative is a row that renders an empty line, found by a reader.
 */
export function describeNotification(notification: InboxNotification): NotificationView {
  const params = readParams(notification.params);
  const campaign = campaignOf(params);
  const named = campaign.title;

  return {
    campaign: named,
    href: hrefOf(notification, campaign.href),
    headline: headlineOf(notification.type, params, named),
  };
}

function headlineOf(
  type: NotificationType,
  params: Record<string, unknown>,
  campaign: string | null,
): string {
  const about = campaign ?? 'a campaign';

  switch (type) {
    // Produced today. The parameter names are `NotificationEventListener`'s.
    case 'PLEDGE_CONFIRMED':
      return `Your pledge of ${amountOr(params, 'total', 'your chosen amount')} to ${about} is confirmed`;
    case 'PLEDGE_EDITED':
      return `Your pledge to ${about} now stands at ${amountOr(params, 'total', 'a new amount')}`;
    case 'PAYMENT_FAILED':
      return `A payment of ${amountOr(params, 'amount', 'your pledge')} for ${about} was declined`;
    case 'GOAL_REACHED':
      return `${capitalise(about)} reached its goal of ${amountOr(params, 'goal', 'what it needed')}`;
    case 'CAMPAIGN_SUCCEEDED':
      return `${capitalise(about)} was funded at ${amountOr(params, 'pledged', 'its closing total')}`;
    case 'CAMPAIGN_UNSUCCESSFUL':
      return `${capitalise(about)} closed without reaching its goal`;
    case 'PROJECT_APPROVED':
      return `${capitalise(about)} has been approved and can be launched`;

    // Not produced yet. #64 owns collection and the payment schedule, #69 the payout, #74
    // the surveys, #80 fulfilment, #83 updates, #84 comments, #90 saving and following, and
    // #87 the push half of all of them. Written now for the reason `EmailComposer` gives:
    // a message whose words are chosen the day its event lands is chosen in a hurry.
    case 'PAYMENT_COLLECTED':
      return `A payment of ${amountOr(params, 'amount', 'your pledge')} for ${about} was collected`;
    case 'FINAL_PAYMENT_WARNING':
      return `Final notice: a payment of ${amountOr(params, 'amount', 'your pledge')} for ${about}`;
    case 'PAYOUT_SENT':
      return `A payout of ${amountOr(params, 'amount', 'your funds')} for ${about} has been sent`;
    case 'DEADLINE_48H':
      return `${capitalise(about)} closes in two days`;
    case 'DEADLINE_24H':
      return `${capitalise(about)} closes tomorrow`;
    case 'NEW_UPDATE_PUBLISHED':
      return `${capitalise(about)} published an update`;
    case 'COMMENT_REPLY':
      return 'Somebody replied to your comment';
    case 'DIRECT_MESSAGE':
      return 'You have a new message';
    case 'SURVEY_AVAILABLE':
      return `Your reward survey for ${about} is ready`;
    case 'SURVEY_OVERDUE':
      return `Your reward survey for ${about} is still unanswered`;
    case 'REWARD_SHIPPED':
      return `Your reward from ${about} has been shipped`;
    case 'FOLLOWED_CREATOR_LAUNCHED':
      return `A creator you follow launched ${about}`;
    case 'LAUNCH_REMINDER':
      return `${capitalise(about)} is about to launch`;
    case 'SAVED_PROJECT_ENDING_SOON':
      return `${capitalise(about)}, which you saved, is ending soon`;
    case 'NEW_DEVICE_SIGN_IN':
      return 'A new device signed in to your account';
  }
}

/**
 * Where the row goes.
 *
 * Three sources, in this order — and it is `EmailComposer`'s order, so that a notification
 * opened from the inbox and the same one opened from an email land in the same place.
 *
 * 1. The type, when the message is not about a campaign. Only the sign-in alert is.
 * 2. The campaign's public path, from the two slugs in the document.
 * 3. Nothing. **A row with no destination is not a link**, rather than a link to the home
 *    page: the platform's own rule is that a live-looking control which goes nowhere useful
 *    is worse than a plain line of text, and unlike an email — where the reader has already
 *    left the application and needs a way back — an inbox row is already inside it.
 */
function hrefOf(notification: InboxNotification, campaignHref: string | null): string | null {
  if (notification.type === 'NEW_DEVICE_SIGN_IN') return '/settings/sessions';
  return campaignHref;
}

/** Sentence case for a phrase that may be a campaign title or the generic stand-in. */
function capitalise(text: string): string {
  return text.charAt(0).toUpperCase() + text.slice(1);
}

/**
 * The categories, in §4.10's order.
 *
 * Declared as a list rather than derived from the response so that the settings page has a
 * stable row order even before it has loaded, and so that a category the service starts
 * sending which nobody has labelled here is a type error.
 */
export const CATEGORIES: readonly NotificationCategory[] = [
  'PLEDGES',
  'CAMPAIGN',
  'PAYMENTS',
  'COMMUNITY',
  'REWARDS',
  'DISCOVERY',
  'SECURITY',
];

/** The three columns of §4.10's table. */
export const CHANNELS: readonly NotificationChannel[] = ['IN_APP', 'EMAIL', 'PUSH'];

const CATEGORY_LABELS: Record<NotificationCategory, string> = {
  PLEDGES: 'Your pledges',
  CAMPAIGN: 'Campaigns you back',
  PAYMENTS: 'Payments and payouts',
  COMMUNITY: 'Comments and messages',
  REWARDS: 'Rewards and surveys',
  DISCOVERY: 'Things you follow',
  SECURITY: 'Account security',
};

const CATEGORY_DESCRIPTIONS: Record<NotificationCategory, string> = {
  PLEDGES: 'A pledge of yours is confirmed or changed.',
  CAMPAIGN: 'A campaign reaches its goal, closes, or is about to.',
  PAYMENTS: 'A payment is taken, declined, or a payout is sent.',
  COMMUNITY: 'Somebody replies to you or sends you a message.',
  REWARDS: 'A survey is waiting, or a reward is on its way.',
  DISCOVERY: 'A creator you follow launches, or something you saved is ending.',
  SECURITY: 'A new device signs in to your account.',
};

const CHANNEL_LABELS: Record<NotificationChannel, string> = {
  IN_APP: 'In app',
  EMAIL: 'Email',
  PUSH: 'Push',
};

const MODE_LABELS: Record<DeliveryMode, string> = {
  OFF: 'Off',
  IMMEDIATE: 'As it happens',
  DIGEST: 'Daily digest',
};

export function categoryLabel(category: NotificationCategory): string {
  return CATEGORY_LABELS[category];
}

export function categoryDescription(category: NotificationCategory): string {
  return CATEGORY_DESCRIPTIONS[category];
}

export function channelLabel(channel: NotificationChannel): string {
  return CHANNEL_LABELS[channel];
}

/**
 * Why a switch the service marks unchangeable cannot be moved.
 *
 * Per category rather than one sentence, because a disabled control with no reason is a
 * control that looks broken — and the reason differs. §4.10 makes only `SECURITY`
 * mandatory today; the fallback is here so that a category that becomes mandatory later
 * shows something true rather than a claim about account safety.
 */
export function mandatoryReason(category: NotificationCategory): string {
  return category === 'SECURITY'
    ? 'Always on. This is how you find out if somebody else reaches your account.'
    : 'Always on. The platform has to be able to reach you about this.';
}

export function modeLabel(mode: DeliveryMode): string {
  return MODE_LABELS[mode];
}

/**
 * The modes this switch may be set to.
 *
 * Built from the response rather than from a rule restated here: `digestOffered` is the
 * service's answer to "can this channel batch", and a client that decided it independently
 * would drift from §4.10 the first time the table changed.
 */
export function modesFor(digestOffered: boolean): readonly DeliveryMode[] {
  return digestOffered ? ['IMMEDIATE', 'DIGEST', 'OFF'] : ['IMMEDIATE', 'OFF'];
}

/**
 * The calendar day an instant falls on, for grouping the inbox.
 *
 * Local rather than UTC: the reader groups by their own day, so a notification that arrived
 * at half past midnight belongs under today for them even where it is still yesterday in
 * UTC.
 */
export function dayKeyOf(iso: string): string {
  const at = new Date(iso);
  return Number.isNaN(at.getTime()) ? 'unknown' : dayKeyOfDate(at);
}

function dayKeyOfDate(at: Date): string {
  return `${at.getFullYear()}-${at.getMonth() + 1}-${at.getDate()}`;
}

/**
 * "Today", "Dünən", "Вчера", or the date — the heading above one group of rows.
 *
 * `now` is a parameter for `formatRelativeTime`'s reason: a function that reads the clock
 * cannot be tested without freezing it.
 *
 * <h2>The two words come from `Intl` rather than from the catalogue — #324</h2>
 *
 * `RelativeTimeFormat` with `numeric: 'auto'` already knows every language's word for today
 * and for yesterday, and it is the same formatter the rest of the application's relative
 * times come out of. Two keys in `messages/*.json` would be two translations to keep in step
 * with a library that ships them — and would be one more thing for this module, which is
 * imported by a client component with no provider above it, to have to be handed.
 *
 * <p>The output is lower case in all four languages and this is a heading, so it goes through
 * `capitalised`, which upper-cases in the reader's own language. That distinction is not
 * pedantry: `toUpperCase` turns Turkish `içinde` into `Içinde`, which is a different word.
 */
export function dayLabelOf(iso: string, now: Date, locale: Locale): string {
  const at = new Date(iso);
  if (Number.isNaN(at.getTime())) return UNDATED[locale];

  const relative = relativeTimeFormat(locale, { numeric: 'auto' }, 'relative');
  const key = dayKeyOfDate(at);
  if (key === dayKeyOfDate(now)) return capitalised(relative.format(0, 'day'), locale);

  const yesterday = new Date(now.getTime());
  yesterday.setDate(yesterday.getDate() - 1);
  if (key === dayKeyOfDate(yesterday)) return capitalised(relative.format(-1, 'day'), locale);

  return dateTimeFormat(locale, { dateStyle: 'full' }, 'day-heading').format(at);
}
