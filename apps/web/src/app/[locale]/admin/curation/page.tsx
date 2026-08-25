import type { Metadata } from 'next';
import { CollectionManager } from '../../../../components/admin/CollectionManager';
import { privatePageMetadata } from '../../../../lib/seo/metadata';

/**
 * §4.11's AD-03: the collection manager — issue #301.
 *
 * <p>Every curated list the platform has, whichever of §4.3's three kinds it is. The three
 * screens beside this one are the same manager asking a narrower question, and
 * `lib/admin/curation.ts` has the argument for why one table and one endpoint set serve all
 * four.
 */
export const metadata: Metadata = privatePageMetadata({
  title: 'Collections',
  description: 'Editorial collections, what is in them, and whether the public can see them.',
});

export default function CurationPage() {
  return (
    <div className="max-w-[880px]">
      <h1 className="text-2xl font-semibold tracking-[-0.03em] text-white sm:text-3xl">
        Collections
      </h1>
      <p className="mt-2 max-w-[62ch] text-sm text-white/64">
        Staff selections, themed lists and open calls — one table, because all three have a slug
        in a URL, translated copy, a publication decision and an edited sequence of campaigns
        behind them. Nothing here can be deleted: a collection anything has happened to carries
        the record of it, so withdrawing one takes its page down and leaves the reasoning
        intact.
      </p>

      <div className="mt-8">
        <CollectionManager allowCreate />
      </div>
    </div>
  );
}
