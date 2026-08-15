import type { Metadata } from 'next';
import { ReviewPanel } from '../../../../../components/campaign-editor/ReviewPanel';

export const metadata: Metadata = {
  title: 'Review',
  description:
    'How complete your campaign is, what moderation has said about it, and submitting it for review.',
};

/**
 * The checklist is read with the account's bearer token from the browser, so this
 * page is a shell and `ReviewPanel` is the client boundary — the same shape as
 * `/projects/[id]/edit/basics` and `/projects/[id]/edit/story`.
 */
export default async function ReviewPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;

  return (
    <main>
      <ReviewPanel projectId={id} />
    </main>
  );
}
