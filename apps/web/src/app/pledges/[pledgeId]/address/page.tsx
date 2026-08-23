import type { Metadata } from 'next';
import Link from 'next/link';
import { AccountPageHeader } from '../../../../components/account/AccountPageHeader';
import { ShippingAddressForm } from '../../../../components/fulfilment/ShippingAddressForm';
import { privatePageMetadata } from '../../../../lib/seo/metadata';

export const metadata: Metadata = privatePageMetadata({
  title: 'Shipping address',
  description: 'Where the reward for this pledge should go.',
});

/**
 * `/pledges/{pledgeId}/address` — §4.8 PM-07, issue #290.
 *
 * <h2>Per pledge, not per account</h2>
 *
 * One person can back two campaigns and want the rewards in two places — one at home and one
 * at an office that can take a parcel during the day. §17.4 stores the address on the pledge
 * for that reason, and a single "my address" screen would quietly overwrite one campaign's
 * destination when somebody changed another's.
 *
 * <h2>The identifier is not validated here</h2>
 *
 * It is handed straight to `GET /v1/pledges/{id}/shipping-address`, which answers 404 for an
 * unknown identifier **and** for one belonging to somebody else — deliberately
 * indistinguishable, so the endpoint cannot be used to ask whether a pledge exists. A client
 * that pre-checked the shape would only be able to reject values the service already rejects,
 * and would tempt the next reader into believing something here had authorised the request.
 */
export default async function ShippingAddressPage({
  params,
}: {
  params: Promise<{ pledgeId: string }>;
}) {
  const { pledgeId } = await params;

  return (
    <>
      <AccountPageHeader title="Shipping address">
        Where this pledge’s reward should go. Each pledge has its own, so changing this one does
        not move anything else. It is encrypted at rest and the creator sees it only when they
        are packing.
      </AccountPageHeader>

      <div className="mt-8">
        <ShippingAddressForm pledgeId={pledgeId} />
      </div>

      <p className="mt-8 text-sm text-white/40">
        <Link
          href="/account/deliveries"
          className="rounded-sm text-white/64 underline underline-offset-4 hover:text-white focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[var(--lime-500)]"
        >
          Back to your deliveries
        </Link>
      </p>
    </>
  );
}
