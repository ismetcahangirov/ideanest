import type { Metadata } from 'next';
import Link from 'next/link';
import { AccountPageHeader } from '../../../components/account/AccountPageHeader';
import { TwoFactorPanel } from '../../../components/settings/TwoFactorPanel';
import { privatePageMetadata } from '../../../lib/seo/metadata';

export const metadata: Metadata = privatePageMetadata({
  title: 'Two-factor authentication',
  description: 'Add a code from your phone to your IdeaNest sign-in.',
});

/**
 * `/settings/security` — §4.1 A-07, issue #278.
 *
 * A shell around a client boundary, like the rest of the account area: the enrolment is one
 * account's, behind a bearer token, and every call on it is a write. There is nothing a server
 * render could produce.
 *
 * **Devices are next door rather than here.** A-09's session list is the other half of "who
 * can get into this account", and folding the two screens together was tempting. They are
 * kept apart because they answer different questions at different moments: the device list is
 * something to check, and this is something to set up once. The navigation puts them beside
 * each other, and this page links across.
 */
export default function SecurityPage() {
  return (
    <>
      <AccountPageHeader title="Two-factor authentication">
        A code from your phone, on top of your password. §4.1 requires it before a payout, so a
        creator will meet it sooner or later. To see which browsers are signed in, go to{' '}
        <Link href="/settings/sessions" className="text-white underline underline-offset-4">
          devices
        </Link>
        .
      </AccountPageHeader>

      <div className="mt-8">
        <TwoFactorPanel />
      </div>
    </>
  );
}
