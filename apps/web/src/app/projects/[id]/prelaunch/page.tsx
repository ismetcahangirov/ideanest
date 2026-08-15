import type { Metadata } from 'next';
import { PrelaunchView } from '../../../../components/prelaunch/PrelaunchView';

export const metadata: Metadata = {
  title: 'Coming soon',
  description: 'A campaign that has not opened yet. Ask to be told when it does.',
};

/**
 * The public pre-launch page — the address a creator shares.
 *
 * NOT under `/edit`, deliberately: this is a link that goes into a social post,
 * and one with "edit" in it invites questions and looks like a mistake.
 *
 * The title here is generic rather than the campaign's, and that is not
 * laziness. Producing a per-campaign title would mean fetching the campaign on
 * the server, and this route has to work for a visitor with no session at all —
 * so the fetch happens in the client boundary, with the same `publicFetch` the
 * reminder form uses, and the metadata stays static. Rich link previews for
 * pre-launch pages need server-side rendering of a public projection, which is
 * the discovery epic's to build; naming it here is better than a half-version.
 */
export default async function PublicPrelaunchPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;

  return (
    <main>
      <PrelaunchView projectId={id} />
    </main>
  );
}
