import type { Metadata } from 'next';
import { AccountPageHeader } from '../../../../components/account/AccountPageHeader';
import { DeliveryList } from '../../../../components/fulfilment/DeliveryList';
import { privatePageMetadata } from '../../../../lib/seo/metadata';

export const metadata: Metadata = privatePageMetadata({
  title: 'Deliveries',
  description: 'Where each of your rewards is, and where it is going.',
});

/**
 * `/account/deliveries` — §4.8 PM-09 and PM-10, issue #290.
 *
 * A shell around a client boundary. Every row is one of this account's own pledges, behind a
 * bearer token.
 */
export default function DeliveriesPage() {
  return (
    <>
      <AccountPageHeader title="Deliveries">
        One row per reward you are owed. A creator records the carrier and the tracking number
        as they pack, so a row with nothing under it has not been sent yet.
      </AccountPageHeader>

      <div className="mt-8">
        <DeliveryList />
      </div>
    </>
  );
}
