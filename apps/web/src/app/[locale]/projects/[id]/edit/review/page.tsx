import type { Metadata } from 'next';
import { ReviewPanel } from '../../../../../../components/campaign-editor/ReviewPanel';
import { privatePageMetadata } from '../../../../../../lib/seo/metadata';
import { editorReviewCopy } from '../../../../../../lib/i18n/shell-copy.server';

export const metadata: Metadata = privatePageMetadata({
  title: 'Review',
  description:
    'How complete your campaign is, what moderation has said about it, and submitting it for review.',
});

/**
 * The checklist is read with the account's bearer token from the browser, so this
 * page is a shell and `ReviewPanel` is the client boundary — the same shape as
 * `/projects/[id]/edit/basics` and `/projects/[id]/edit/story`.
 */
export default async function ReviewPage({ params }: { params: Promise<{ id: string }> }) {
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
  return <ReviewPanel projectId={id} copy={await editorReviewCopy()} />;
}
