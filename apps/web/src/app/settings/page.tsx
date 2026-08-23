import { redirect } from 'next/navigation';

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
export default function SettingsIndexPage() {
  redirect('/settings/notifications');
}
