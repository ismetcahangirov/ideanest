import type { Metadata } from 'next';
import { Link } from '../../../../i18n/navigation';
import { AccountPageHeader } from '../../../../components/account/AccountPageHeader';
import { EmailChangePanel } from '../../../../components/settings/EmailChangePanel';
import { privatePageMetadata } from '../../../../lib/seo/metadata';

export const metadata: Metadata = privatePageMetadata({
  title: 'Email address',
  description: 'Move your IdeaNest account to a different email address.',
});

/**
 * `/settings/email` — §4.1 A-12, issue #277.
 *
 * A shell around a client boundary, like the rest of the account area: the address is one
 * account's, behind a bearer token, and the only call on the page is a write. There is nothing
 * a server render could produce — and, for the same reason `SessionProvider` gives, nothing it
 * could read either: the refresh cookie is issued on `Path=/v1/auth`, so a request for this
 * page carries no session for `cookies()` to find.
 *
 * <h2>It is a page of its own rather than a section of `/settings/security`</h2>
 *
 * The security screen is two-factor authentication, which is something to set up once. This is
 * something to change when an address stops working, and the two are reached from different
 * moments. `/settings/password` is next door for the same reason and is linked from here,
 * because the two credentials are the pair somebody thinks about together — but they are not
 * one form: A-13 signs every session out and A-12 signs none out, and putting the two
 * submissions on one screen would put one warning over both.
 */
export default function EmailSettingsPage() {
  return (
    <>
      <AccountPageHeader title="Email address">
        The address you sign in with, and where everything IdeaNest sends you goes. To change
        your password instead, go to{' '}
        <Link href="/settings/password" className="text-white underline underline-offset-4">
          password
        </Link>
        .
      </AccountPageHeader>

      <div className="mt-8">
        <EmailChangePanel />
      </div>
    </>
  );
}
