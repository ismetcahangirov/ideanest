import { redirect } from 'next/navigation';

/**
 * `/projects/[id]/edit` is not a page, it is the editor's front door.
 *
 * The redirect happens on the server because it needs no data: the first tab is
 * always the basics, and sending the browser there before any JavaScript runs
 * means the creator never sees an empty frame decide where to go. It also keeps
 * one address canonical, so a bookmark and a shared link both name a real tab.
 */
export default async function ProjectEditPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  redirect(`/projects/${encodeURIComponent(id)}/edit/basics`);
}
