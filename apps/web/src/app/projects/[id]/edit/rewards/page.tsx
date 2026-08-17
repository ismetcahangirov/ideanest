import type { Metadata } from 'next';
import { RewardsPanel } from '../../../../../components/campaign-editor/RewardsPanel';
import { privatePageMetadata } from '../../../../../lib/seo/metadata';

export const metadata: Metadata = privatePageMetadata({
  title: 'Rewards',
  description:
    'The items your campaign produces, and the reward tiers backers choose from — prices, quantities, delivery, and order.',
});

/**
 * The project is loaded with the account's bearer token from the browser, so
 * this page is a shell and `RewardsPanel` is the client boundary — the same
 * shape as `/projects/[id]/edit/basics` and `/projects/[id]/edit/story`.
 */
export default async function RewardsPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;

  return (
    <main>
      <RewardsPanel projectId={id} />
    </main>
  );
}
