import type { Metadata } from 'next';
import { NewProjectForm } from '../../../components/campaign-editor/NewProjectForm';
import { privatePageMetadata } from '../../../lib/seo/metadata';

export const metadata: Metadata = privatePageMetadata({
  title: 'Start a project',
  description: 'Name your campaign and start editing it. Nothing is public until you submit it.',
});

/**
 * A shell around a client form.
 *
 * Creating a draft needs the account's access token, which lives in memory in
 * the browser and nowhere else (`src/lib/api/access-token.ts`), so there is
 * nothing here a server render could do.
 */
export default function NewProjectPage() {
  return (
    <main className="mx-auto w-full max-w-[560px] px-5 py-10 sm:px-6 sm:py-14">
      <h1 className="text-2xl font-semibold tracking-[-0.03em] text-white sm:text-3xl">
        Start a project
      </h1>
      <p className="mt-2 text-sm text-white/64">
        Give it a working title. The editor saves as you type, and nothing is visible to anybody
        else until you submit the campaign for review.
      </p>

      <NewProjectForm />
    </main>
  );
}
