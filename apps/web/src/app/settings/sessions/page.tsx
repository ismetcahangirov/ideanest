import type { Metadata } from 'next';
import { SessionsPanel } from '../../../components/sessions/SessionsPanel';
import { privatePageMetadata } from '../../../lib/seo/metadata';

export const metadata: Metadata = privatePageMetadata({
  title: 'Active sessions',
  description: 'Review the devices signed in to your IdeaNest account and sign out of any of them.',
});

/**
 * The list is per-account and authenticated with a bearer token held in memory,
 * so there is nothing here a server render could produce — the page is a shell
 * and `SessionsPanel` is the client boundary.
 */
export default function SessionsPage() {
  return (
    <main className="mx-auto w-full max-w-[720px] px-5 py-10 sm:px-6 sm:py-14">
      <h1 className="text-2xl font-semibold tracking-[-0.03em] text-white sm:text-3xl">
        Active sessions
      </h1>
      <p className="mt-2 max-w-[52ch] text-sm text-white/64">
        Every device currently signed in to your account. If you do not recognise one, sign it out —
        it stops being able to refresh within fifteen minutes.
      </p>

      <div className="mt-8">
        <SessionsPanel />
      </div>
    </main>
  );
}
