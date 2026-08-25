import { localeOrDefault } from '../../../lib/i18n/locale';
import { localeRedirect } from '../../../i18n/redirect';

/**
 * `/account` redirects to the first screen under it, exactly as `/settings` does.
 *
 * Saved projects rather than surveys: it is the screen that is worth reading when nothing is
 * owed, and a landing page that opens on an empty survey list reads as an account with
 * nothing in it.
 */
export default async function AccountIndexPage({
  params,
}: {
  params: Promise<{ locale: string }>;
}) {
  const { locale } = await params;

  /*
   * The language is passed explicitly because there is nothing to read it from here: this
   * component renders no subtree and the URL is what decides the language now. A redirect
   * that quietly defaulted to English would move a reader out of their language on a page
   * with no visible output for them to notice it on. `i18n/redirect.ts` carries the argument.
   */
  localeRedirect('/account/saved', localeOrDefault(locale));
}
