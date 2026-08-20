import type { Metadata } from 'next';
import Link from 'next/link';
import { PreferencesPanel } from '../../../components/notifications/PreferencesPanel';
import { privatePageMetadata } from '../../../lib/seo/metadata';

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
 */
export default function NotificationSettingsPage() {
  return (
    <main className="mx-auto w-full max-w-[720px] px-5 py-10 sm:px-6 sm:py-14">
      <h1 className="text-2xl font-semibold tracking-[-0.03em] text-white sm:text-3xl">
        Notification settings
      </h1>
      <p className="mt-2 max-w-[56ch] text-sm text-white/64">
        Choose what you are told and how it reaches you. <strong>As it happens</strong> sends
        each one on its own; <strong>daily digest</strong> collects them into one message a
        day, where the channel supports it. <strong>In app</strong> is what fills your{' '}
        <Link href="/notifications" className="text-white underline underline-offset-4">
          notifications
        </Link>
        .
      </p>
      <p className="mt-2 max-w-[56ch] text-sm text-white/56">
        Changes save as you make them.
      </p>

      <div className="mt-8">
        <PreferencesPanel />
      </div>
    </main>
  );
}
