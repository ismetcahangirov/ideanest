import type { Metadata } from 'next';
import { Link } from '../../../i18n/navigation';
import { InboxPanel } from '../../../components/notifications/InboxPanel';
import { privatePageMetadata } from '../../../lib/seo/metadata';

export const metadata: Metadata = privatePageMetadata({
  title: 'Notifications',
  description: 'Everything IdeaNest has told you, newest first.',
});

/**
 * The in-app inbox — §4.10's third channel, and #88.
 *
 * The list is one account's, authenticated with a bearer token held in memory, so there is
 * nothing here a server render could produce: the page is a shell and `InboxPanel` is the
 * client boundary. Same arrangement as `/settings/sessions`, and for the same reason.
 *
 * At `/notifications` rather than under `/settings`, because this is not a setting — it is
 * a place somebody reads. What the account is *sent* is the settings page, and the link
 * below is how a reader gets from "too many of these" to the switch that stops them.
 */
export default function NotificationsPage() {
  return (
    /*
      A `<div>` and not a `<main>` since #345. `app/notifications/layout.tsx` puts this page
      inside `SiteShell`, which owns the only `<main>` on the document and is the skip link's
      target. The column width and the padding stay here: the shell sets neither, and every
      page inside it draws its own measure.
    */
    <div className="mx-auto w-full max-w-[720px] px-5 py-10 sm:px-6 sm:py-14">
      <h1 className="text-2xl font-semibold tracking-[-0.03em] text-white sm:text-3xl">
        Notifications
      </h1>
      <p className="mt-2 max-w-[52ch] text-sm text-white/64">
        Everything the platform has told you, newest first. Opening one marks it read.{' '}
        <Link href="/settings/notifications" className="text-white underline underline-offset-4">
          Change what you are sent
        </Link>
        .
      </p>

      <div className="mt-8">
        <InboxPanel />
      </div>
    </div>
  );
}
