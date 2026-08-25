import { formatMoney } from '../../lib/money';
import type { PublicRewardTier } from '../../lib/seo/structured-data/product';
import { getTranslations } from 'next-intl/server';

/**
 * The reward tiers a backer chooses between — §4.4's right-hand column, server-rendered.
 *
 * <h2>What this is and what it is not</h2>
 *
 * A **read-only list**, in the initial HTML, because a crawler that cannot see what a
 * campaign offers cannot describe it and a link unfurler has nothing to preview. It is the
 * same data `structured-data/product.ts` turns into `Product` nodes on this page, read from
 * the same fetch, so the machine-readable and human-readable halves of the page cannot
 * disagree about what a tier costs.
 *
 * **Choosing one is not here.** The pledge flow is `/projects/{id}/back` (§4.5), it needs a
 * session, a price lock and a reservation, and it is a client boundary with its own tests.
 * A "Back this project" control that led there is the next thing this page wants; what it
 * must not become is a second, half-implemented checkout that reserves nothing.
 *
 * <h2>Sold out is shown, never hidden</h2>
 *
 * PL-01, and `PublicReward.remainingQuantity` states it: a tier with zero places left stays
 * on the page. Hiding it tells a backer the campaign never offered it, and the interface can
 * then not explain why the total they were quoted a minute ago is unavailable. `null` means
 * unlimited and is the common case; zero is a real number and is not the same thing.
 *
 * <h2>Colour</h2>
 *
 * No lime. §8.1 maps `--lime-500` to a *selected* reward tier — an active choice — and
 * nothing on this list is selected, because nothing here can be chosen yet. A lime tier
 * that did nothing would be the decoration §1.1 forbids.
 */
export interface CampaignRewardsProps {
  readonly tiers: readonly PublicRewardTier[];
}

export async function CampaignRewards({ tiers }: CampaignRewardsProps) {
  const t = await getTranslations('campaign.rewards');

  if (tiers.length === 0) return null;

  return (
    <section aria-labelledby="campaign-rewards" className="flex flex-col gap-4">
      <h2 id="campaign-rewards" className="text-xl font-medium tracking-[-0.02em] text-white">
        {t('heading')}
      </h2>

      <ul className="flex flex-col gap-3">
        {tiers.map((tier) => (
          <li
            key={tier.id}
            className="flex flex-col gap-2 rounded-lg border border-white/8 bg-surface-2 p-5"
          >
            <p className="font-display text-2xl font-semibold tracking-[-0.03em] text-white tabular-nums">
              {formatMoney(tier.price)}
            </p>
            <h3 className="text-base font-medium text-white">{tier.title}</h3>
            {tier.description !== null && <p className="text-sm text-reading">{tier.description}</p>}

            {tier.remainingQuantity !== null && (
              /*
               * A word and a number, never a colour on its own (§9.2). "Sold out" is the
               * fact a reader needs; a greyed-out card would tell somebody with a
               * colour-vision deficiency nothing at all.
               */
              <p className="text-sm text-white/64">
                {tier.remainingQuantity === 0
                  ? 'Sold out'
                  : `${tier.remainingQuantity} of this reward left`}
              </p>
            )}
          </li>
        ))}
      </ul>
    </section>
  );
}
