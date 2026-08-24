import type { Metadata } from 'next';
import { ReportDetail } from '../../../../components/admin/ReportDetail';
import { privatePageMetadata } from '../../../../lib/seo/metadata';

/**
 * §4.11's AD-01: one submission, its notes, and the full decision history — issue #296.
 *
 * <p><strong>The heading does not name the report.</strong> A title carrying the reason
 * or the target would mean resolving the report on the server, and the console has no
 * server-side session to resolve it with — the access token lives in the browser
 * (`lib/api/client.ts`). So the page is titled by what it is and the panel says which
 * report it is, which is also the right answer for a screen whose subject is a complaint
 * somebody made about a named person.
 *
 * <p>`privatePageMetadata` for the reason every console route gives, and one of its own:
 * this page quotes what one person wrote about another and shows the account that wrote
 * it.
 */
export const metadata: Metadata = privatePageMetadata({
  title: 'Report',
  description: 'One complaint, the decision taken on it, and everything recorded against it.',
});

export default async function ReportDetailPage({
  params,
}: {
  readonly params: Promise<{ readonly reportId: string }>;
}) {
  const { reportId } = await params;

  return (
    <div className="max-w-[880px]">
      <h1 className="text-2xl font-semibold tracking-[-0.03em] text-white sm:text-3xl">
        Report
      </h1>
      <p className="mt-2 max-w-[62ch] text-sm text-white/64">
        The complaint, what was decided about it, and every privileged action recorded
        against it — including the ones that were refused.
      </p>

      <div className="mt-8">
        <ReportDetail reportId={reportId} />
      </div>
    </div>
  );
}
