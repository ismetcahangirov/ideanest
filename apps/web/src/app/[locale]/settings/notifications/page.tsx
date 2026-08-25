import type { Metadata } from 'next';
import { Link } from '../../../../i18n/navigation';
import { AccountPageHeader } from '../../../../components/account/AccountPageHeader';
import { PreferencesPanel } from '../../../../components/notifications/PreferencesPanel';
import { privatePageMetadata } from '../../../../lib/seo/metadata';

export const metadata: Metadata = privatePageMetadata({
  title: 'Notification settings',
  description: 'Choose what IdeaNest tells you, and how it reaches you.',
});

/**
 * Per-category, per-channel delivery control — §4.10, and #89.
 *
 * A shell around a client boundary, like `/settings/sessions`: the settings are one
 * account's, behind a bearer token, and there is nothing a server render could produce.
 *
 * The page states the two things somebody has to know before they touch a control — that a
 * digest is once a day rather than never, and that in-app delivery is what fills the inbox
 * — because both are otherwise learned by turning something off and waiting to find out
 * what stopped arriving.
 *
 * **It lost its own `<main>` and its own page padding in #275**, for the reason
 * `/settings/sessions` states: the account area's layout owns both, and `SiteShell` owns the
 * single `<main>` on the page.
 */
export default function NotificationSettingsPage() {
  return (
    <>
      <AccountPageHeader title="Notifications">
        Choose what you are told and how it reaches you. <strong>As it happens</strong> sends
        each one on its own; <strong>daily digest</strong> collects them into one message a day,
        where the channel supports it. <strong>In app</strong> is what fills your{' '}
        <Link href="/notifications" className="text-white underline underline-offset-4">
          notifications
        </Link>
        . Changes save as you make them.
      </AccountPageHeader>

      <div className="mt-8">
        <PreferencesPanel />
      </div>
    </>
  );
}
