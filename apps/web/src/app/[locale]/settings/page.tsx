import { localeOrDefault } from '../../../lib/i18n/locale';
import { localeRedirect } from '../../../i18n/redirect';

/**
 * `/settings` has no screen of its own — it redirects to the first entry under it.
 *
 * The same arrangement `/projects/[id]/edit` uses. A landing page here would be a page whose
 * entire content is the navigation already beside it on every screen in the area, and a 404
 * would be worse: `/settings` is a URL people type.
 *
 * Notifications rather than security, because it is the settings screen somebody most often
 * arrives wanting — every notification email links to it.
 */
export default async function SettingsIndexPage({
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
  localeRedirect('/settings/notifications', localeOrDefault(locale));
}
