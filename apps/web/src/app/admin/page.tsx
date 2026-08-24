import type { Metadata } from 'next';
import { ConsoleIndex } from '../../components/admin/ConsoleIndex';
import { privatePageMetadata } from '../../lib/seo/metadata';

/**
 * The console's front door — §4.11, issue #294.
 *
 * <p>A page rather than a redirect to the first screen, which is what `/settings` and
 * `/account` are. The difference is that those two are lists of things somebody already knows
 * they want to change, and this is a list of things that mostly do not exist yet: epic #259's
 * definition of done is that every one of §4.11's sixteen modules "has either a screen or an
 * open blocker naming what it needs", and a redirect has nowhere to say that.
 *
 * <p>`privatePageMetadata` for the reason the two screens that predate the console give: an
 * administration surface has no business in an index, so this emits `noindex, nofollow` and
 * no social card.
 */
export const metadata: Metadata = privatePageMetadata({
  title: 'Administration console',
  description: 'The platform modules, which of them have a screen, and what the rest wait on.',
});

export default function AdminConsolePage() {
  return <ConsoleIndex />;
}
