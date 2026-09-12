import type { Metadata } from 'next';
import { FaqPanel } from '../../../../../../components/campaign-editor/FaqPanel';
import { privatePageMetadata } from '../../../../../../lib/seo/metadata';
import { getTranslations } from 'next-intl/server';
import { editorFaqCopy } from '../../../../../../lib/i18n/shell-copy.server';

export async function generateMetadata(): Promise<Metadata> {
  const t = await getTranslations('editor');

  /*
   * A function rather than a `const` since #459: a `const metadata` cannot read a request, so
   * the tab title followed the build rather than the reader. It is the one piece of this screen
   * somebody sees before the page paints.
   *
   * <p>The title is the section's own name — `frame.tabs.faq`, the same key the navigation
   * draws — rather than a second spelling under `pages`. A tab called one thing in the browser's
   * tab strip and another in the editor's own navigation is one word translated twice.
   */
  return privatePageMetadata({
    title: t('frame.tabs.faq'),
    description: t('pages.faq.metaDescription'),
  });
}

/**
 * The project is loaded with the account's bearer token from the browser, so
 * this page is a shell and `FaqPanel` is the client boundary — the same shape as
 * `/projects/[id]/edit/basics`, `/story` and `/rewards`.
 *
 * The write endpoints behind the panel are authorised by the `MANAGE_FAQ`
 * project capability rather than by `EDIT_BASICS` (docs/architecture.md §4.4).
 * Nothing here checks it: the service is the thing that decides, and a client
 * that hid the tab on its own reading of a capability list would be a second,
 * weaker copy of an authorisation rule. A collaborator without the grant sees
 * the tab and is refused with a sentence, which is the honest failure.
 */
export default async function FaqPage({ params }: { params: Promise<{ id: string }> }) {
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
  return <FaqPanel projectId={id} copy={await editorFaqCopy()} />;
}
