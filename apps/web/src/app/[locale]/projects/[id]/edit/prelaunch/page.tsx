import type { Metadata } from 'next';
import { PrelaunchPanel } from '../../../../../../components/campaign-editor/PrelaunchPanel';
import { privatePageMetadata } from '../../../../../../lib/seo/metadata';
import { editorPrelaunchCopy } from '../../../../../../lib/i18n/shell-copy.server';

export const metadata: Metadata = privatePageMetadata({
  title: 'Pre-launch',
  description:
    'Open a pre-launch page, share the link, and collect the people who want to be told when your campaign opens.',
});

/**
 * The project is loaded with the account's bearer token from the browser, so this
 * page is a shell and `PrelaunchPanel` is the client boundary — the same shape as
 * `/projects/[id]/edit/basics`.
 *
 * The PUBLIC pre-launch page is a different route, `/projects/[id]/prelaunch`,
 * and deliberately not under `edit`: it is the address a creator shares, and a
 * link with "edit" in it is a link people ask questions about.
 */
export default async function PrelaunchEditorPage({ params }: { params: Promise<{ id: string }> }) {
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
  return <PrelaunchPanel projectId={id} copy={await editorPrelaunchCopy()} />;
}
