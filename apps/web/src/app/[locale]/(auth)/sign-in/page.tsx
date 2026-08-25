import type { Metadata } from 'next';
import { Suspense } from 'react';
import { AuthPageHeader } from '../../../../components/auth/AuthPageHeader';
import { SignInForm } from '../../../../components/auth/SignInForm';
import { privatePageMetadata } from '../../../../lib/seo/metadata';

/**
 * `/sign-in` — §4.1 A-03, issue #268.
 *
 * <h2>`noindex`, and it is in robots.txt too</h2>
 *
 * There is nothing here to index: no content, and the only thing a crawler could do with the
 * page is spend budget on it. `lib/seo/indexability.ts` disallows the authentication paths and
 * this is the second lock. The private shape also strips the inherited Open Graph block, so a
 * sign-in URL pasted into a chat does not unfurl as an invitation.
 *
 * <h2>The `Suspense` boundary is required, not defensive</h2>
 *
 * `SignInForm` reads `?next=` with `useSearchParams`, and a component that does so must sit
 * under a boundary or Next refuses to statically render the route. The fallback is the same
 * heading the form renders under, so there is no flash of a different layout — only the
 * controls arriving.
 */

export const metadata: Metadata = privatePageMetadata({
  title: 'Sign in',
  description: 'Sign in to IdeaNest.',
});

export default function SignInPage() {
  return (
    <>
      <AuthPageHeader title="Sign in">
        Back to your pledges, your campaigns, and everything you are following.
      </AuthPageHeader>

      <Suspense fallback={<p className="text-white/40">Loading the form…</p>}>
        <SignInForm />
      </Suspense>
    </>
  );
}
