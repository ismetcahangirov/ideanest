import { ApiError, type Problem } from '../api/problem';

/**
 * A refusal from the pledge module, in words a backer can act on.
 *
 * <h2>Why the wording is ours here and the service's on Discovery</h2>
 *
 * `DiscoveryView` renders the service's own `title` and `detail` and says why:
 * the endpoint knows which of its rules refused the request and the client does
 * not. Checkout is the other case. The contract names every refusal this surface
 * can meet — six on the draft and two on the confirm — and each of them has a
 * DIFFERENT RECOVERY: one wants a different tier, one wants a different country,
 * one wants a new reservation, one wants the amount changed, and one wants the
 * backer to go and look at the pledge they already have. A sentence that is
 * accurate but does not say which of those to do is a dead end on the screen
 * where somebody is trying to give money.
 *
 * So the heading is ours, chosen with the recovery, and the service's `detail` is
 * kept underneath it when there is one — it is the half that names the tier, the
 * country, or the amount, and it is written by the code that knows which. Nothing
 * branches on `detail`; `code` is the only thing matched against (§10.4).
 */

/** What the interface should offer next. */
export type Recovery =
  /** Reserve again from scratch: the previous reservation is gone. */
  | 'redraft'
  /** The selection has to change before anything can be reserved. */
  | 'change-selection'
  /** Try the same request again; nothing about the selection is wrong. */
  | 'retry'
  /** Nothing on this screen will help. */
  | 'none';

export interface CheckoutFailure {
  /** The stable machine-readable reason, or null when there was no problem body. */
  readonly code: string | null;
  readonly status: number | null;
  readonly title: string;
  /** The service's own sentence when it wrote one, otherwise ours. */
  readonly detail: string;
  readonly recovery: Recovery;
  /**
   * §10.4's `meta.availableAlternatives`: the tiers the service suggests instead
   * of the sold-out one. Ids, which the caller resolves against the reward list
   * it already holds — the service sends ids because a title is prose and this is
   * the same response that says not to branch on prose.
   */
  readonly alternatives: readonly string[];
  /** The control the refusal is about, when it is about one. */
  readonly field: 'contribution' | 'destination' | null;
  /**
   * True when the key that produced this refusal must not be sent again.
   *
   * `RESERVATION_EXPIRED` and `IDEMPOTENCY_KEY_REUSED` only — see
   * `./idempotency`, which explains why replaying a key after an expiry hands
   * back the expired draft and loops.
   */
  readonly retireKey: boolean;
}

/** Reads `meta.availableAlternatives` without trusting its shape. */
function alternativesIn(problem: Problem | null): readonly string[] {
  const value = problem?.meta?.['availableAlternatives'];
  if (!Array.isArray(value)) return [];
  return value.filter((entry): entry is string => typeof entry === 'string');
}

interface Wording {
  title: string;
  detail: string;
  recovery: Recovery;
  field?: 'contribution' | 'destination';
  retireKey?: boolean;
}

/**
 * The contract's codes, each with the recovery that belongs to it.
 *
 * A record rather than a switch so that a code with no entry is obvious — it
 * falls through to the service's own prose, which is the honest answer for a
 * refusal this build has never heard of, rather than to a sentence that guesses.
 */
const WORDING: Record<string, Wording> = {
  REWARD_SOLD_OUT: {
    title: 'That reward has just gone',
    detail: 'Somebody took the last one while you were choosing. Nothing has been reserved.',
    recovery: 'change-selection',
  },
  PLEDGE_ALREADY_EXISTS: {
    title: 'You are already backing this campaign',
    detail:
      'One pledge per campaign. Changing what you chose means editing the pledge you have, which is not something this build can do yet.',
    recovery: 'none',
  },
  IDEMPOTENCY_KEY_REUSED: {
    // Not the backer's mistake, and the wording does not imply it is. It means
    // this client sent one key for two different bodies, which is a bug in the
    // client; retiring the key makes the next attempt work rather than loop.
    title: 'That request did not match the one before it',
    detail: 'Nothing was reserved. Try again.',
    recovery: 'retry',
    retireKey: true,
  },
  SHIPPING_DESTINATION_UNPRICED: {
    title: 'The creator does not post to that destination',
    detail:
      'They have not set a delivery cost for it, so there is no honest amount to charge you. Choose somewhere else, or drop the item that is posted.',
    recovery: 'change-selection',
    field: 'destination',
  },
  CONTRIBUTION_BELOW_REWARD_PRICE: {
    title: 'That is less than the reward costs',
    detail: 'Give at least the price of the tier you chose, or choose a cheaper one.',
    recovery: 'change-selection',
    field: 'contribution',
  },
  PROJECT_NOT_LIVE: {
    title: 'This campaign is not taking pledges',
    detail: 'It may have closed, or it may not have opened yet. Nothing was reserved.',
    recovery: 'none',
  },
  RESERVATION_EXPIRED: {
    title: 'Your reward was only held for five minutes',
    detail:
      'The hold has ended and the stock has gone back to the campaign. Nothing was confirmed and no card was involved. Reserve it again to carry on.',
    recovery: 'redraft',
    retireKey: true,
  },
  PLEDGE_NOT_DRAFT: {
    title: 'This pledge has already been dealt with',
    detail: 'It is confirmed or cancelled, so there is nothing left to confirm.',
    recovery: 'none',
  },
};

/**
 * Anything a checkout request can throw, as something to render.
 *
 * A thrown value that is not an `ApiError` is a failure to reach the service at
 * all — an offline browser, DNS, a proxy — and it is reported as that rather than
 * as a refusal. The distinction matters here more than anywhere: "the campaign
 * refused your pledge" and "your connection dropped" call for opposite next
 * moves, and only one of them is safe to repeat without thinking.
 */
export function describeFailure(cause: unknown): CheckoutFailure {
  if (!(cause instanceof ApiError)) {
    return {
      code: null,
      status: null,
      title: 'The service could not be reached',
      detail:
        'Nothing was sent, or nothing came back. Check your connection and try again — trying again is safe, and cannot pledge twice.',
      recovery: 'retry',
      alternatives: [],
      field: null,
      retireKey: false,
    };
  }

  const problem = cause.problem;
  const code = problem?.code ?? null;
  const wording = code === null ? undefined : WORDING[code];

  if (wording === undefined) {
    if (cause.status === 401) {
      return {
        code,
        status: cause.status,
        title: 'You are not signed in',
        detail: 'Sign in and start again. Nothing was reserved.',
        recovery: 'none',
        alternatives: [],
        field: null,
        retireKey: false,
      };
    }

    return {
      code,
      status: cause.status,
      title: problem?.title ?? 'That did not work',
      detail:
        problem?.detail ??
        'The service refused the request and did not say why. Nothing was reserved.',
      recovery: 'retry',
      alternatives: alternativesIn(problem),
      field: null,
      retireKey: false,
    };
  }

  return {
    code,
    status: cause.status,
    title: wording.title,
    /*
     * OURS, NOT THE SERVICE'S, FOR THE EIGHT CODES ABOVE — and this is the one
     * place this repository overrides RFC 9457 prose. The service's sentence
     * states the fact ("the Super Early Bird tier has no remaining places");
     * the screen is already stating that fact, because the tier is marked sold
     * out three centimetres away. What it does not state is what to do next,
     * and on the screen where somebody is trying to give money that is the only
     * half worth the space. An unknown code still falls through to the
     * service's own words above, because there the fact is all anybody has.
     */
    detail: wording.detail,
    recovery: wording.recovery,
    alternatives: alternativesIn(problem),
    field: wording.field ?? null,
    retireKey: wording.retireKey ?? false,
  };
}
