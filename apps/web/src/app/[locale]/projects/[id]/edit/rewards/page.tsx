import type { Metadata } from 'next';
import { RewardsPanel } from '../../../../../../components/campaign-editor/RewardsPanel';
import { privatePageMetadata } from '../../../../../../lib/seo/metadata';
import { getTranslations } from 'next-intl/server';
import { editorRewardsCopy } from '../../../../../../lib/i18n/shell-copy.server';

export async function generateMetadata(): Promise<Metadata> {
  const t = await getTranslations('editor');

  /*
   * A function rather than a `const` since #459: a `const metadata` cannot read a request, so
   * the tab title followed the build rather than the reader. It is the one piece of this screen
   * somebody sees before the page paints.
   *
   * <p>The title is the section's own name — `frame.tabs.rewards`, the same key the navigation
   * draws — rather than a second spelling under `pages`. A tab called one thing in the browser's
   * tab strip and another in the editor's own navigation is one word translated twice.
   */
  return privatePageMetadata({
    title: t('frame.tabs.rewards'),
    description: t('pages.rewards.metaDescription'),
  });
}

/**
 * The project is loaded with the account's bearer token from the browser, so
 * this page is a shell and `RewardsPanel` is the client boundary — the same
 * shape as `/projects/[id]/edit/basics` and `/projects/[id]/edit/story`.
 */
export default async function RewardsPage({ params }: { params: Promise<{ id: string }> }) {
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
  return <RewardsPanel projectId={id} copy={await editorRewardsCopy()} />;
}
