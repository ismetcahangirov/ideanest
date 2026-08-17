import type { Metadata } from 'next';
import { StoryPanel } from '../../../../../components/campaign-editor/StoryPanel';
import { privatePageMetadata } from '../../../../../lib/seo/metadata';

export const metadata: Metadata = privatePageMetadata({
  title: 'Story',
  description:
    'The long-form story of your campaign, its risks and challenges, and its earlier versions.',
});

/**
 * The project is loaded with the account's bearer token from the browser, so this
 * page is a shell and `StoryPanel` is the client boundary — the same shape as
 * `/projects/[id]/edit/basics`.
 */
export default async function StoryPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;

  return (
    <main>
      <StoryPanel projectId={id} />
    </main>
  );
}
