import type { FulfilmentStatus } from './api';

/**
 * Turning a fulfilment row into words a backer understands.
 *
 * Pure, and `now` is a parameter wherever the clock matters — `lib/time.ts`'s rule, for the
 * same reason: the tests do not have to freeze time and every row on one render is measured
 * from one instant.
 *
 * <h2>The status names are the creator's, not the backer's</h2>
 *
 * `PREPARING`, `SHIPPED`, `DELIVERED`, `RETURNED` are written from the side that packs the
 * parcel. A backer reading "Preparing" learns nothing about whether anything is wrong, so each
 * one is paired with a sentence that says what it means for them and what, if anything, they
 * should do. `RETURNED` is the one that needs it most: it is the only status that asks the
 * reader to act.
 */

export interface StatusDescription {
  /** The word on the tag. */
  readonly label: string;
  /** What it means for the person waiting. */
  readonly detail: string;
  /**
   * `Tag`'s variant.
   *
   * Never the only carrier — docs/ui-kit.md §9.2 — which is why `label` exists and is beside
   * it. §8.1 reads `--danger` as a state that needs attention, and a parcel that came back is
   * the only one of the four that does.
   */
  readonly tone: 'default' | 'success' | 'warning' | 'danger';
}

const DESCRIPTIONS: Readonly<Record<FulfilmentStatus, StatusDescription>> = {
  PREPARING: {
    label: 'Preparing',
    detail: 'The creator has not sent this yet.',
    tone: 'default',
  },
  SHIPPED: {
    label: 'On its way',
    detail: 'It is with a carrier.',
    tone: 'warning',
  },
  DELIVERED: {
    label: 'Delivered',
    detail: 'The carrier says it arrived.',
    tone: 'success',
  },
  RETURNED: {
    label: 'Came back',
    detail: 'It could not be delivered and is back with the creator. Check your address.',
    tone: 'danger',
  },
};

/**
 * The description for a status, or a readable fallback for one this build does not know.
 *
 * A newer service could add a fifth. Showing the raw value is honest — it is at least what the
 * service said — where a blank tag would tell a backer nothing and a guessed one would tell
 * them something wrong about where their parcel is.
 */
export function describeStatus(status: FulfilmentStatus | string): StatusDescription {
  const known = DESCRIPTIONS[status as FulfilmentStatus];
  if (known !== undefined) return known;

  return {
    label: status,
    detail: 'This platform does not have a description for that status yet.',
    tone: 'default',
  };
}

/**
 * Whether a tracking link may be followed.
 *
 * **The creator types this URL**, so it is untrusted input that ends up in an `href`. Only
 * `http` and `https` are allowed: a `javascript:` URL in an anchor is script execution on this
 * origin, which is where the session lives, and `data:` is a document of the creator's
 * choosing rendered as though it were ours. Anything else is shown as text beside the tracking
 * number rather than being made clickable.
 */
export function isFollowableTrackingUrl(url: string | null): boolean {
  if (url === null || url.trim() === '') return false;

  try {
    const parsed = new URL(url);
    return parsed.protocol === 'http:' || parsed.protocol === 'https:';
  } catch {
    return false;
  }
}
