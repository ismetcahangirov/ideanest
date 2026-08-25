import type { Metadata } from 'next';
import { PlacementEditor } from '../../../../../components/admin/PlacementEditor';
import { privatePageMetadata } from '../../../../../lib/seo/metadata';

/**
 * §4.11's AD-03: the placement editor — issue #303.
 *
 * <p>§4.13's WS-04 makes the home page the one surface that is entirely editorial, and this is
 * what orders it. `PlacementEditor` states plainly that placement today is one integer per
 * collection rather than a slot editor, because an interface that implied more would stop
 * working the moment somebody built the real thing.
 */
export const metadata: Metadata = privatePageMetadata({
  title: 'Placement',
  description: 'The order curated collections appear in.',
});

export default function PlacementsPage() {
  return (
    <div className="max-w-[880px]">
      <h1 className="text-2xl font-semibold tracking-[-0.03em] text-white sm:text-3xl">
        Placement
      </h1>
      <p className="mt-2 max-w-[62ch] text-sm text-white/64">
        Where curated collections appear, which today is the order they appear in. The home page
        and the browse pages read the same list, so this is one decision rather than one per
        surface.
      </p>

      <div className="mt-8">
        <PlacementEditor />
      </div>
    </div>
  );
}
