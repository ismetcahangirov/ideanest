import type { Metadata } from 'next';
import { StoryPanel } from '../../../../../../components/campaign-editor/StoryPanel';
import { privatePageMetadata } from '../../../../../../lib/seo/metadata';
import { getTranslations } from 'next-intl/server';
import { editorStoryCopy } from '../../../../../../lib/i18n/shell-copy.server';

export async function generateMetadata(): Promise<Metadata> {
  const t = await getTranslations('editor');

  /*
   * A function rather than a `const` since #459: a `const metadata` cannot read a request, so
   * the tab title followed the build rather than the reader. It is the one piece of this screen
   * somebody sees before the page paints.
   *
   * <p>The title is the section's own name — `frame.tabs.story`, the same key the navigation
   * draws — rather than a second spelling under `pages`. A tab called one thing in the browser's
   * tab strip and another in the editor's own navigation is one word translated twice.
   */
  return privatePageMetadata({
    title: t('frame.tabs.story'),
    description: t('pages.story.metaDescription'),
  });
}

/**
 * The project is loaded with the account's bearer token from the browser, so this
 * page is a shell and `StoryPanel` is the client boundary — the same shape as
 * `/projects/[id]/edit/basics`.
 */
export default async function StoryPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;

  /*
   * No `<main>` since #347. `app/projects/[id]/edit/layout.tsx` puts the editor inside
   * `SiteShell`, which owns the only `<main>` on the document and is the skip link's target.
   * `EditorShell` draws this page's own column and heading, so the element that was here
   * carried a landmark and nothing else.
   *
   * The copy is resolved here, on the server, and handed down — issue #459. The panel is a
   * client component because the form autosaves as it is typed, so it cannot read the
   * catalogue itself; `lib/i18n/editor-copy.ts` carries why that is a prop rather than a
   * provider.
   */
  return <StoryPanel projectId={id} copy={await editorStoryCopy()} />;
}
