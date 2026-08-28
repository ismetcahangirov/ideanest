import type { Metadata } from 'next';
import { AccountPageHeader } from '../../../../components/account/AccountPageHeader';
import { DeliveryList } from '../../../../components/fulfilment/DeliveryList';
import { privatePageMetadata } from '../../../../lib/seo/metadata';
import { getTranslations } from 'next-intl/server';
import { deliveryListCopyFrom } from '../../../../lib/i18n/fulfilment-copy';

export async function generateMetadata(): Promise<Metadata> {
  const t = await getTranslations('account.pages.deliveries');

  /*
   * A function rather than a `const` since #324: the tab title is the one piece of
   * this screen a reader sees before the page paints.
   */
  return privatePageMetadata({ title: t('metaTitle'), description: t('metaDescription') });
}

/**
 * `/account/deliveries` — §4.8 PM-09 and PM-10, issue #290.
 *
 * A shell around a client boundary. Every row is one of this account's own pledges, behind a
 * bearer token.
 */
export default async function DeliveriesPage() {
  const t = await getTranslations('account.pages.deliveries');

  return (
    <>
      <AccountPageHeader title={t('title')}>
        {t('intro')}
      </AccountPageHeader>

      <div className="mt-8">
        <DeliveryList copy={deliveryListCopyFrom(await getTranslations('account.fulfilment'))} />
      </div>
    </>
  );
}
