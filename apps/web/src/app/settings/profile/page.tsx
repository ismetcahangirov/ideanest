import type { Metadata } from 'next';
import Link from 'next/link';
import { AccountPageHeader } from '../../../components/account/AccountPageHeader';
import { ProfileEditorPanel } from '../../../components/profile/ProfileEditorPanel';
import { privatePageMetadata } from '../../../lib/seo/metadata';

export const metadata: Metadata = privatePageMetadata({
  title: 'Profile',
  description:
    'Your name, biography, picture, website, location and links, as everybody else sees them.',
});

/**
 * `/settings/profile` — §4.2 P-01 to P-03, issue #276.
 *
 * <h2>A shell around a client boundary, like the rest of the account area</h2>
 *
 * `GET /v1/me/profile` is one account's own data behind a bearer token and the service
 * answers it `private, no-store`, so there is nothing a server render could produce — and,
 * for the reason `SessionProvider` gives, nothing it could read either: the refresh cookie is
 * issued on `Path=/v1/auth`, so a request for this page carries no session for `cookies()` to
 * find. `/settings/email` and `/settings/password` are the same shape for the same reason.
 *
 * <h2>Why the editor is one page rather than a section of an existing one</h2>
 *
 * P-07's visibility switch is on `/settings/privacy` and stays there. It decides whether the
 * profile answers **at all**, which is a question about who can see what — the same question
 * the data export and the account closure answer — and `ProfileVisibilityPanel` is read
 * alongside those two. This page decides what is *on* the profile once it does answer. Moving
 * the switch here would put "hide me completely" at the top of a form about a biography, and
 * moving the form there would put six fields between the export and the closure that
 * `app/settings/privacy/page.tsx` explains must be read as one argument.
 *
 * <h2>What is still missing, said plainly</h2>
 *
 * **P-01 is "avatar upload and crop" and this page performs neither.** There is no object
 * storage and no media table; §13.1's ingestion pipeline is a different epic.
 * `ProfileAvatarField` takes the address of a picture that is already published and says so
 * on screen, which is the same position `components/campaign-editor/CoverImageField` takes
 * about a campaign's cover.
 *
 * <h2>Motion: none</h2>
 *
 * docs/motion-system.md §5 gives "authentication, **account settings**" a budget of none —
 * 150ms colour on controls. Nothing on this route imports `@ideanest/ui/motion` and there is
 * no `FadeUp`: §5's reason is that this is work rather than exploration, and its own note
 * about an error message applies to a save confirmation too.
 */
export default function ProfileSettingsPage() {
  return (
    <>
      <AccountPageHeader title="Profile">
        What strangers see when they open your profile or a campaign you created. To hide the
        page altogether, go to{' '}
        <Link href="/settings/privacy" className="text-white underline underline-offset-4">
          privacy
        </Link>
        .
      </AccountPageHeader>

      <div className="mt-8">
        <ProfileEditorPanel />
      </div>
    </>
  );
}
