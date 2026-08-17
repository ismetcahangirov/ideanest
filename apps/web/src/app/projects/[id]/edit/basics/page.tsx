import type { Metadata } from 'next';
import { BasicsPanel } from '../../../../../components/campaign-editor/BasicsPanel';
import { privatePageMetadata } from '../../../../../lib/seo/metadata';

export const metadata: Metadata = privatePageMetadata({
  title: 'Basics',
  description: 'The title, summary, category, goal, duration, and cover image of your campaign.',
});

/**
 * The project is loaded with the account's bearer token from the browser, so
 * this page is a shell and `BasicsPanel` is the client boundary — the same shape
 * as `/settings/sessions`.
 */
export default async function BasicsPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;

  return (
    <main>
      <BasicsPanel projectId={id} />
    </main>
  );
}
