import { localeOrDefault } from '../../../../../lib/i18n/locale';
import { localeRedirect } from '../../../../../i18n/redirect';

/**
 * `/projects/[id]/edit` is not a page, it is the editor's front door.
 *
 * The redirect happens on the server because it needs no data: the first tab is
 * always the basics, and sending the browser there before any JavaScript runs
 * means the creator never sees an empty frame decide where to go. It also keeps
 * one address canonical, so a bookmark and a shared link both name a real tab.
 */
export default async function ProjectEditPage({
  params,
}: {
  params: Promise<{ locale: string; id: string }>;
}) {
  const { locale, id } = await params;

  /*
   * The language is passed explicitly because there is nothing to read it from here: this
   * component renders no subtree and the URL is what decides the language now. A redirect
   * that quietly defaulted to English would move a reader out of their language on a page
   * with no visible output for them to notice it on. `i18n/redirect.ts` carries the argument.
   */
  localeRedirect(`/projects/${encodeURIComponent(id)}/edit/basics`, localeOrDefault(locale));
}
