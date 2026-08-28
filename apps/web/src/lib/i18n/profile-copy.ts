import type { PluralForms } from './plurals';

/**
 * Every word `/u/[slug]` draws — issue #324.
 *
 * <h2>Why the card's status vocabulary is its own and not `campaign.state`</h2>
 *
 * The catalogue already carries `campaign.state`, and it is a different vocabulary for the
 * same enum on purpose rather than by accident. That one is written for somebody deciding
 * whether to back a campaign — `PRELAUNCH` reads "Coming soon" — while a profile's archive is
 * a list of statuses and reads "Pre-launch". The two lists also do not cover the same states:
 * an archive shows `SCHEDULED` and `COLLECTING`, and a campaign page shows `CANCELED`.
 *
 * Reconciling the wording would be a copy decision about what a badge should say, not a
 * translation, so this change keeps both and says so here rather than quietly changing English
 * on one of the two screens.
 *
 * <h2>The backer count is `PluralForms` rather than an ICU string</h2>
 *
 * `campaign.backers` is ICU because the campaign page resolves it on the server. A profile's
 * grid appends cards after a click, so each card's count arrives in the browser, where
 * formatting ICU would mean the `use-intl` runtime in the bundle. `lib/i18n/plurals.ts`
 * carries the reasoning and the CLDR lookup.
 */

/**
 * A message lookup rooted at `profile`.
 *
 * `raw` is used for the states table, the plural forms, and every sentence carrying a
 * placeholder: `t('x')` on a template is a formatting error in next-intl, which renders the
 * key's own path rather than the sentence. `fillPlaceholders` puts the value in where it is
 * known, which is in the component.
 */
export interface ProfileRawTranslator {
  (key: string): string;
  raw(key: string): unknown;
}

export interface ProfileCardCopy {
  /** Keyed by the service's state name; a state not in the table renders as itself. */
  readonly states: Readonly<Record<string, string>>;
  /** Carries `{percent}`. The accessible name of the progress bar. */
  readonly progressLabel: string;
  /** Carries `{percent}`. */
  readonly funded: string;
  /** Carries `{amount}`, already formatted by `lib/money.ts` against the campaign's currency. */
  readonly ofGoal: string;
  readonly backers: PluralForms;
}

export interface ProfileGridCopy {
  readonly failedTitle: string;
  readonly failedBody: string;
  readonly createdEmptyTitle: string;
  /** Carries `{name}`. */
  readonly createdEmptyBody: string;
  readonly backedEmptyTitle: string;
  /** Carries `{name}`. */
  readonly backedEmptyBody: string;
  readonly showMore: string;
  readonly showMoreCreated: string;
  readonly showMoreBacked: string;
  readonly loading: string;
  readonly nextFailedTitle: string;
  readonly refused: string;
  readonly unreachable: string;
  readonly card: ProfileCardCopy;
}

export interface ProfileAboutCopy {
  /** Carries `{name}`. */
  readonly heading: string;
  /** Carries `{name}`. */
  readonly empty: string;
  readonly basedIn: string;
  readonly website: string;
  readonly since: string;
  readonly elsewhere: string;
}

export interface ProfileCopy {
  /** Carries `{name}`. */
  readonly avatarAlt: string;
  readonly tabsLabel: string;
  readonly tabs: {
    readonly created: string;
    readonly backed: string;
    readonly about: string;
  };
  readonly about: ProfileAboutCopy;
  readonly grid: ProfileGridCopy;
}

function cardCopyFrom(t: ProfileRawTranslator): ProfileCardCopy {
  return {
    states: t.raw('card.states') as Readonly<Record<string, string>>,
    progressLabel: String(t.raw('card.progressLabel')),
    funded: String(t.raw('card.funded')),
    ofGoal: String(t.raw('card.ofGoal')),
    backers: t.raw('card.backers') as PluralForms,
  };
}

export function profileGridCopyFrom(t: ProfileRawTranslator): ProfileGridCopy {
  return {
    failedTitle: t('grid.failedTitle'),
    failedBody: t('grid.failedBody'),
    createdEmptyTitle: t('grid.createdEmptyTitle'),
    createdEmptyBody: String(t.raw('grid.createdEmptyBody')),
    backedEmptyTitle: t('grid.backedEmptyTitle'),
    backedEmptyBody: String(t.raw('grid.backedEmptyBody')),
    showMore: t('grid.showMore'),
    showMoreCreated: t('grid.showMoreCreated'),
    showMoreBacked: t('grid.showMoreBacked'),
    loading: t('grid.loading'),
    nextFailedTitle: t('grid.nextFailedTitle'),
    refused: t('grid.refused'),
    unreachable: t('grid.unreachable'),
    card: cardCopyFrom(t),
  };
}

export function profileCopyFrom(t: ProfileRawTranslator): ProfileCopy {
  return {
    avatarAlt: String(t.raw('avatarAlt')),
    tabsLabel: t('tabsLabel'),
    tabs: {
      created: t('tabs.created'),
      backed: t('tabs.backed'),
      about: t('tabs.about'),
    },
    about: {
      heading: String(t.raw('about.heading')),
      empty: String(t.raw('about.empty')),
      basedIn: t('about.basedIn'),
      website: t('about.website'),
      since: t('about.since'),
      elsewhere: t('about.elsewhere'),
    },
    grid: profileGridCopyFrom(t),
  };
}
