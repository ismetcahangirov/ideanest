import type { Metadata } from 'next';
import { TaxonomyManager } from '../../../../components/admin/TaxonomyManager';
import { privatePageMetadata } from '../../../../lib/seo/metadata';

/**
 * §4.11's AD-08: category and tag management with translations — §4.3, issue #309.
 *
 * <p>§4.3 requires the taxonomy be editable without a deployment. The tables have existed
 * since V6 and V11; what did not exist was any way to write to them.
 *
 * <p>`privatePageMetadata` for the reason every console route gives: these pages are
 * per-person, they are not for a crawler, and several of them name people.
 */
export const metadata: Metadata = privatePageMetadata({
  title: 'Taxonomy',
  description: 'Categories, subcategories and tags, with a translation per locale.',
});

export default function TaxonomyPage() {
  return (
    <div className="max-w-[920px]">
      <h1 className="text-2xl font-semibold tracking-[-0.03em] text-white sm:text-3xl">
        Taxonomy
      </h1>
      <p className="mt-2 max-w-[62ch] text-sm text-white/64">
        Handles are permanent — they are in the public URL of every campaign filed under them, and
        the platform has no redirect table. Names and translations are what this screen edits.
      </p>

      <div className="mt-8">
        <TaxonomyManager />
      </div>
    </div>
  );
}
