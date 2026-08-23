import type { Metadata } from 'next';
import { PasswordResetRequestForm } from '../../../components/auth/PasswordResetRequestForm';
import { privatePageMetadata } from '../../../lib/seo/metadata';

/**
 * `/reset-password` — §4.1 A-06, issue #271.
 *
 * <h2>It is in `app/(auth)` and not under `/settings`</h2>
 *
 * Everybody who reaches this screen is somebody who cannot sign in, so it must be outside the
 * session guard — `lib/session/private-routes.ts` covers `/settings` and `/account`, and a
 * reset behind either would be a lock whose key is inside the room. The route group also gives
 * it the frame the rest of the authentication screens use: a wordmark that goes home, the 26rem
 * column, and no site header offering ten other things to somebody trying to do one.
 *
 * <h2>No `Suspense` boundary, unlike its sibling</h2>
 *
 * `PasswordResetRequestForm` reads nothing from the URL, so this route stays statically
 * renderable without one. `/reset-password/confirm` does read `?token=` and does have the
 * boundary — the difference is deliberate rather than an inconsistency, and adding a boundary
 * here would be ceremony around a component that cannot suspend.
 *
 * <h2>The heading belongs to the component</h2>
 *
 * `RegisterForm`'s arrangement, for its reason: which of two headings is correct depends on a
 * state only the component holds — the form, or the "if that address has an account" screen
 * that replaces it.
 *
 * <h2>`noindex`, and disallowed in `robots.txt` besides</h2>
 *
 * There is nothing here to index and the only thing a crawler could do with the page is submit
 * nothing and spend budget. `lib/seo/indexability.ts` lists the path with the other
 * authentication routes; this is the second lock. The private shape also strips the inherited
 * Open Graph block, so a reset URL pasted into a chat does not unfurl as an invitation.
 */

export const metadata: Metadata = privatePageMetadata({
  title: 'Reset your password',
  description: 'Get a link that sets a new password for your IdeaNest account.',
});

export default function ResetPasswordPage() {
  return <PasswordResetRequestForm />;
}
