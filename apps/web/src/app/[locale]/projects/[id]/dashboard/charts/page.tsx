import { FundingCharts } from '../../../../../../components/dashboard/FundingCharts';

/**
 * The dashboard's charts panel — §4.7's CD-02, CD-07 and CD-08, issue 96.
 *
 * <p>A Server Component that renders a client one and fetches nothing, for the reason the
 * overview page gives: both reads are behind a bearer token and the service answers both
 * `no-store`.
 *
 * <p>The route is `charts` rather than `analytics`, which is what the service calls one of
 * the two endpoints behind it. A creator does not have an analytics; they have a chart of
 * what their campaign raised, and the URL is the one thing on this screen they might type.
 */
export default async function FundingChartsPage({
  params,
}: {
  readonly params: Promise<{ readonly id: string }>;
}) {
  const { id } = await params;

  return <FundingCharts projectId={id} />;
}
