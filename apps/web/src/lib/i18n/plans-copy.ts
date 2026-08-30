/**
 * Every word the pricing page draws.
 *
 * <h2>Why the whole of it arrives as one prop</h2>
 *
 * `PlanChooser` is a client component and has to be: it reads what this visitor holds after
 * hydration, and buying a plan is a request it makes. It therefore cannot call
 * `getTranslations`. The page is a server component and is the single entry point, so it
 * resolves this object once and hands it down — `lib/i18n/shell-copy.ts` carries the
 * measurement behind the pattern, and a `NextIntlClientProvider` was tried and cost +24.7 KiB
 * on every route in the group.
 *
 * <h2>The exhaustive shape is not ceremony on this page</h2>
 *
 * Two of these strings are the difference between a creator understanding what they bought
 * and not. `pending.body` is the only place anybody is told that a paid plan waits for the
 * platform to record a transfer, and `limits.campaigns` is what says why a submission was
 * refused. A missing key renders its own dotted path in production, so an index signature
 * here would put `pricing.pending.body` on the screen where somebody has just paid money.
 */

/** What one plan card says about a bound. `null` on the wire means no bound at all. */
export interface PlanLimitsCopy {
  /** Carries `{count}`. */
  readonly campaigns: string;
  readonly campaignsUnlimited: string;
  /** Carries `{amount}`, already formatted as money. */
  readonly goalCeiling: string;
  readonly goalUnlimited: string;
}

/** The banner a creator meets when a refused submission sent them here. */
export interface FromSubmitCopy {
  readonly title: string;
  readonly body: string;
  readonly back: string;
}

/** What the page says about the plan this visitor already holds. */
export interface HeldCopy {
  readonly heading: string;
  /** Carries `{plan}` and `{date}`. */
  readonly activeUntil: string;
  /** Carries `{plan}`. */
  readonly pendingTitle: string;
  readonly pendingBody: string;
  /** Carries `{plan}` and `{date}`. */
  readonly endingOn: string;
  /** Carries `{plan}` and `{date}`. */
  readonly lapsed: string;
  readonly cancel: string;
  readonly cancelling: string;
  readonly currentTag: string;
}

export interface PricingCopy {
  readonly title: string;
  readonly intro: string;
  readonly loading: string;
  readonly unavailable: string;
  readonly empty: string;
  readonly perMonth: string;
  readonly perYear: string;
  readonly free: string;
  readonly choose: string;
  readonly choosing: string;
  readonly signedOut: string;
  readonly signIn: string;
  readonly limits: PlanLimitsCopy;
  readonly fromSubmit: FromSubmitCopy;
  readonly held: HeldCopy;
  readonly errors: {
    readonly alreadySubscribed: string;
    readonly notOnSale: string;
    readonly signedOut: string;
    readonly generic: string;
  };
}

/**
 * The subset of `next-intl`'s translator this module uses.
 *
 * Declared structurally rather than imported, following every other copy module here: the
 * tests build one from a plain object, and depending on the library's own type would make
 * that a cast.
 */
export interface PricingTranslator {
  (key: string): string;
  raw(key: string): unknown;
}

export function pricingCopyFrom(t: PricingTranslator): PricingCopy {
  return {
    title: t('title'),
    intro: t('intro'),
    loading: t('loading'),
    unavailable: t('unavailable'),
    empty: t('empty'),
    perMonth: t('perMonth'),
    perYear: t('perYear'),
    free: t('free'),
    choose: t('choose'),
    choosing: t('choosing'),
    signedOut: t('signedOut'),
    signIn: t('signIn'),
    limits: {
      // `t.raw` for the templates, because `t('key')` on a message holding a placeholder
      // renders the key's own path — `test-copy.ts` refuses it, and this is the four places
      // on the page where a number is filled in.
      campaigns: String(t.raw('limits.campaigns')),
      campaignsUnlimited: t('limits.campaignsUnlimited'),
      goalCeiling: String(t.raw('limits.goalCeiling')),
      goalUnlimited: t('limits.goalUnlimited'),
    },
    fromSubmit: {
      title: t('fromSubmit.title'),
      body: t('fromSubmit.body'),
      back: t('fromSubmit.back'),
    },
    held: {
      heading: t('held.heading'),
      activeUntil: String(t.raw('held.activeUntil')),
      pendingTitle: String(t.raw('held.pendingTitle')),
      pendingBody: t('held.pendingBody'),
      endingOn: String(t.raw('held.endingOn')),
      lapsed: String(t.raw('held.lapsed')),
      cancel: t('held.cancel'),
      cancelling: t('held.cancelling'),
      currentTag: t('held.currentTag'),
    },
    errors: {
      alreadySubscribed: t('errors.alreadySubscribed'),
      notOnSale: t('errors.notOnSale'),
      signedOut: t('errors.signedOut'),
      generic: t('errors.generic'),
    },
  };
}
