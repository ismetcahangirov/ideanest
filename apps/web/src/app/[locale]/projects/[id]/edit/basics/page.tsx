import type { Metadata } from 'next';
import { BasicsPanel } from '../../../../../../components/campaign-editor/BasicsPanel';
import { privatePageMetadata } from '../../../../../../lib/seo/metadata';
import { editorBasicsCopy } from '../../../../../../lib/i18n/shell-copy.server';

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

  /*
   * No `<main>` since #347. `app/projects/[id]/edit/layout.tsx` puts the editor inside
   * `SiteShell`, which owns the only `<main>` on the document and is the skip link's target.
   * `EditorShell` draws this page's own column and heading, so the element that was here
   * carried a landmark and nothing else.
   */
  /*
   * The copy is resolved here, on the server, and handed down — issue #459. The panel
   * is a client component because the form autosaves as it is typed, so it cannot read
   * the catalogue itself; `lib/i18n/editor-copy.ts` carries why that is a prop rather
   * than a provider.
   */
  return <BasicsPanel projectId={id} copy={await editorBasicsCopy()} />;
}
