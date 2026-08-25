/**
 * Every word the checkout draws — issue #324.
 *
 * <h2>Why the whole of it arrives as one prop</h2>
 *
 * All six components under `components/checkout` are client components, and they have to be:
 * the reservation clock ticks, the amount field validates as it is typed, the summary
 * recomputes on every choice. None of them can call `getTranslations`.
 *
 * `app/[locale]/projects/[id]/back/page.tsx` is a server component and is the single entry
 * point, so it resolves this object once and hands it to `CheckoutView`, which passes each
 * section down. `lib/i18n/shell-copy.ts` carries the measurement behind the pattern — a
 * `NextIntlClientProvider` was tried and cost +24.7 KiB on every route on the site.
 *
 * <h2>The checkout is the one surface where a translation is a money question</h2>
 *
 * "Nothing is charged unless the campaign reaches its goal" is not decoration; it is the
 * promise §5.1 makes and the single fact a first-time backer most often has wrong. A
 * translation that softened it, or that rendered a key name because somebody added a string
 * in English only, would be a page taking a financial commitment while failing to state its
 * terms. `errors` carries the same weight from the other side: a validation message that does
 * not appear leaves a form that refuses to submit and will not say why.
 *
 * That is why the shape is exhaustive and typed rather than an index signature. A missing
 * key is a compile error here, not a `settings.pages.x.y` on the screen where the money is.
 */
export interface CheckoutCopy {
  readonly title: string;
  readonly progress: string;
  readonly steps: {
    readonly choose: string;
    readonly review: string;
    readonly confirmed: string;
  };
  readonly intro: string;
  readonly loading: string;
  readonly expired: string;
  readonly reserveAgain: string;
  readonly tryAgain: string;
  readonly stillAvailable: string;
  readonly reward: {
    readonly legend: string;
    readonly hint: string;
    readonly none: string;
    readonly noneHint: string;
    readonly soldOut: string;
    readonly inPerson: string;
    readonly digital: string;
    readonly earlyBird: string;
    readonly featured: string;
    readonly chooseOne: string;
    readonly digitalItem: string;
    /** Carries `{month}`; filled by `fillPlaceholders` where the date is known. */
    readonly estimatedDelivery: string;
    readonly postedDomestic: string;
    readonly postedWorldwide: string;
  };
  readonly addons: {
    readonly heading: string;
    readonly intro: string;
    readonly soldOut: string;
  };
  readonly destination: {
    readonly label: string;
    readonly hint: string;
    readonly placeholder: string;
  };
  readonly contribution: {
    readonly legend: string;
    readonly hint: string;
  };
  readonly summary: {
    readonly label: string;
    readonly empty: string;
    readonly pending: string;
    readonly pledge: string;
    readonly addons: string;
    readonly bonus: string;
    readonly delivery: string;
    readonly tax: string;
    readonly total: string;
    /** The three the panel prints on its own lines rather than in the review block. */
    readonly noReward: string;
    readonly yourSupport: string;
    readonly rewardLine: string;
  };
  readonly payment: {
    readonly heading: string;
    readonly none: string;
    readonly body: string;
    readonly later: string;
  };
  readonly anonymous: {
    readonly label: string;
    readonly hint: string;
    readonly shown: string;
    readonly yourName: string;
  };
  readonly reserved: string;
  readonly review: {
    readonly heading: string;
    readonly reward: string;
    readonly addon: string;
    readonly noReward: string;
    readonly deliveredTo: string;
    readonly listedAs: string;
    readonly notCharged: string;
    readonly change: string;
    readonly reserve: string;
    readonly confirm: string;
    readonly confirming: string;
  };
  readonly done: {
    readonly announced: string;
    readonly heading: string;
    readonly next: string;
    readonly backed: string;
    readonly reference: string;
    readonly keepReference: string;
    readonly released: string;
    readonly noCard: string;
    readonly noMethod: string;
    readonly methodKept: string;
    readonly expired: string;
  };
  readonly errors: {
    readonly amountMissing: string;
    readonly amountPrecision: string;
    readonly amountTooLarge: string;
    readonly amountTooSmall: string;
    readonly destinationMissing: string;
    readonly totalTooSmall: string;
    /*
     * The five below carry a placeholder — `{minimum}`, `{price}`, `{lines}` — and are
     * PLAIN TEMPLATE STRINGS rather than functions of their value.
     *
     * A function cannot cross the server/client boundary: React serialises props into the
     * flight payload and a closure has nothing to serialise, so a `(price) => string` here
     * would fail `next build` and nothing else. These take simple substitution — no plural,
     * no gender, no ordinal — so `fillPlaceholders` below does the work where the value is
     * known, which is in the browser.
     */
    readonly amountMissingMinimum: string;
    readonly amountComma: string;
    readonly amountNotANumber: string;
    readonly belowRewardPrice: string;
    readonly destinationUnpriced: string;
  };
}

/**
 * `{price}` in a template, replaced with the value.
 *
 * Deliberately not an ICU formatter. next-intl's would need its runtime in the browser,
 * which is the cost `shell-copy.ts` measured and refused; these five messages carry one
 * placeholder each and no grammatical agreement, so a replacement is the whole of what ICU
 * would do for them. Anything that needs a plural — the search count, for instance — is
 * formatted on the server where the real formatter already is.
 *
 * A placeholder with no value is left as written rather than blanked: `{price}` on screen is
 * a defect somebody reports, and an empty gap in a sentence about money is one they do not.
 */
export function fillPlaceholders(template: string, values: Readonly<Record<string, string>>): string {
  return template.replace(/\{(\w+)\}/gu, (whole, name: string) => values[name] ?? whole);
}

/**
 * A message lookup, narrowed to what this builder needs.
 *
 * Taking one rather than calling `getTranslations` keeps this module free of
 * `next-intl/server`, so a component test can build the same object out of `messages/*.json`
 * and assert against the words the checkout will actually draw.
 */
export interface CheckoutTranslator {
  (key: string): string;
  /**
   * The message before ICU formatting.
   *
   * REQUIRED FOR THE FIVE TEMPLATES. `t('errors.belowRewardPrice')` asks the formatter to
   * resolve `{price}`, is given no value for it, and throws — so the key that carries a
   * placeholder has to be read raw and filled where the value exists, in the browser.
   */
  raw(key: string): unknown;
}

export function checkoutCopyFrom(t: CheckoutTranslator): CheckoutCopy {
  return {
    title: t('title'),
    progress: t('progress'),
    steps: {
      choose: t('steps.choose'),
      review: t('steps.review'),
      confirmed: t('steps.confirmed'),
    },
    intro: t('intro'),
    loading: t('loading'),
    expired: t('expired'),
    reserveAgain: t('reserveAgain'),
    tryAgain: t('tryAgain'),
    stillAvailable: t('stillAvailable'),
    reward: {
      legend: t('reward.legend'),
      hint: t('reward.hint'),
      none: t('reward.none'),
      noneHint: t('reward.noneHint'),
      soldOut: t('reward.soldOut'),
      inPerson: t('reward.inPerson'),
      digital: t('reward.digital'),
      earlyBird: t('reward.earlyBird'),
      featured: t('reward.featured'),
      chooseOne: t('reward.chooseOne'),
      digitalItem: t('reward.digitalItem'),
      estimatedDelivery: String(t.raw('reward.estimatedDelivery')),
      postedDomestic: t('reward.postedDomestic'),
      postedWorldwide: t('reward.postedWorldwide'),
    },
    addons: {
      heading: t('addons.heading'),
      intro: t('addons.intro'),
      soldOut: t('addons.soldOut'),
    },
    destination: {
      label: t('destination.label'),
      hint: t('destination.hint'),
      placeholder: t('destination.placeholder'),
    },
    contribution: {
      legend: t('contribution.legend'),
      hint: t('contribution.hint'),
    },
    summary: {
      label: t('summary.label'),
      empty: t('summary.empty'),
      pending: t('summary.pending'),
      pledge: t('summary.pledge'),
      addons: t('summary.addons'),
      bonus: t('summary.bonus'),
      delivery: t('summary.delivery'),
      tax: t('summary.tax'),
      total: t('summary.total'),
      noReward: t('summary.noReward'),
      yourSupport: t('summary.yourSupport'),
      rewardLine: t('summary.rewardLine'),
    },
    payment: {
      heading: t('payment.heading'),
      none: t('payment.none'),
      body: t('payment.body'),
      later: t('payment.later'),
    },
    anonymous: {
      label: t('anonymous.label'),
      hint: t('anonymous.hint'),
      shown: t('anonymous.shown'),
      yourName: t('anonymous.yourName'),
    },
    reserved: t('reserved'),
    review: {
      heading: t('review.heading'),
      reward: t('review.reward'),
      addon: t('review.addon'),
      noReward: t('review.noReward'),
      deliveredTo: t('review.deliveredTo'),
      listedAs: t('review.listedAs'),
      notCharged: t('review.notCharged'),
      change: t('review.change'),
      reserve: t('review.reserve'),
      confirm: t('review.confirm'),
      confirming: t('review.confirming'),
    },
    done: {
      announced: t('done.announced'),
      heading: t('done.heading'),
      next: t('done.next'),
      backed: t('done.backed'),
      reference: t('done.reference'),
      keepReference: t('done.keepReference'),
      released: t('done.released'),
      noCard: t('done.noCard'),
      noMethod: t('done.noMethod'),
      methodKept: t('done.methodKept'),
      expired: t('done.expired'),
    },
    errors: {
      amountMissing: t('errors.amountMissing'),
      amountPrecision: t('errors.amountPrecision'),
      amountTooLarge: t('errors.amountTooLarge'),
      amountTooSmall: t('errors.amountTooSmall'),
      destinationMissing: t('errors.destinationMissing'),
      totalTooSmall: t('errors.totalTooSmall'),
      amountMissingMinimum: String(t.raw('errors.amountMissingMinimum')),
      /* No placeholder in these two, but read the same way so the five stay one group. */
      amountComma: t('errors.amountComma'),
      amountNotANumber: t('errors.amountNotANumber'),
      belowRewardPrice: String(t.raw('errors.belowRewardPrice')),
      destinationUnpriced: String(t.raw('errors.destinationUnpriced')),
    },
  };
}
