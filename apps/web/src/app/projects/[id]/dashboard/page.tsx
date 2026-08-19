import { DashboardOverview } from '../../../../components/dashboard/DashboardOverview';

/**
 * The dashboard's first panel — §4.7's CD-01.
 *
 * <h2>A Server Component that renders a client one, and fetches nothing</h2>
 *
 * That is not an oversight. The figures below are one creator's view of their own money
 * behind a bearer token, and `lib/api/server.ts` is deliberately anonymous — a server
 * render that varied by session is a page nothing can cache and is exactly what #119's
 * server-rendering argument does *not* apply to. The panel fetches after hydration, like
 * the moderation queue.
 *
 * <p>What this file does is take the campaign identifier out of the route and hand it
 * over. The heading is the panel's rather than this page's, because the campaign's title
 * arrives with the same response as the numbers, and a heading rendered here would either
 * be a second fetch or a placeholder that changes after paint.
 */
export default async function DashboardOverviewPage({
  params,
}: {
  readonly params: Promise<{ readonly id: string }>;
}) {
  const { id } = await params;

  return <DashboardOverview projectId={id} />;
}
