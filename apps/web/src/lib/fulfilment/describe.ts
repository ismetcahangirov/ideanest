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

/**
 * The tone each status is shown in. The words are `account.fulfilment.status` — issue #324.
 *
 * The tone stays here because it is a design decision rather than copy: §8.1 reads `--danger`
 * as a state that needs attention, and a parcel that came back is the only one of the four
 * that does. It is never the only carrier of the meaning (docs/ui-kit.md §9.2), which is what
 * the label beside it is for.
 */
const TONES: Readonly<Record<FulfilmentStatus, StatusDescription['tone']>> = {
  PREPARING: 'default',
  SHIPPED: 'warning',
  DELIVERED: 'success',
  RETURNED: 'danger',
};

/**
 * The description for a status, or a readable fallback for one this build does not know.
 *
 * A newer service could add a fifth. Showing the raw value is honest — it is at least what the
 * service said — where a blank tag would tell a backer nothing and a guessed one would tell
 * them something wrong about where their parcel is.
 */
export function describeStatus(
  status: FulfilmentStatus | string,
  copy: StatusCopy,
): StatusDescription {
  const tone = TONES[status as FulfilmentStatus];
  const label = copy.status[status];

  if (tone !== undefined && label !== undefined) {
    return { label, detail: copy.statusDetail[status] ?? copy.statusDetail['unknown'] ?? '', tone };
  }

  /*
   * A newer service could add a fifth. Showing the raw value is honest — it is at least what
   * the service said — where a blank tag would tell a backer nothing and a guessed one would
   * tell them something wrong about where their parcel is.
   */
  return {
    label: status,
    detail: copy.statusDetail['unknown'] ?? '',
    tone: 'default',
  };
}

/** The two tables `describeStatus` reads, as `DeliveryListCopy` carries them. */
export interface StatusCopy {
  readonly status: Readonly<Record<string, string>>;
  readonly statusDetail: Readonly<Record<string, string>>;
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
