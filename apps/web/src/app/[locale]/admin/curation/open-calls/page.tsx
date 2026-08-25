import type { Metadata } from 'next';
import { OpenCallManager } from '../../../../../components/admin/OpenCallManager';
import { privatePageMetadata } from '../../../../../lib/seo/metadata';

/**
 * §4.11's AD-03: the open call manager — issue #302.
 *
 * <p>§4.3's Programmes: a themed list with a window it is open in. The window is what makes
 * the kind different from the other two, and it is the one thing this screen edits.
 */
export const metadata: Metadata = privatePageMetadata({
  title: 'Open calls',
  description: 'Themed programmes, and the windows they accept entries in.',
});

export default function OpenCallsPage() {
  return (
    <div className="max-w-[880px]">
      <h1 className="text-2xl font-semibold tracking-[-0.03em] text-white sm:text-3xl">
        Open calls
      </h1>
      <p className="mt-2 max-w-[62ch] text-sm text-white/64">
        A programme is a themed collection with dates. Those dates decide more than they look:
        the public read excludes a collection whose window has closed, so a finished programme
        stops answering rather than showing as finished.
      </p>

      <div className="mt-8">
        <OpenCallManager />
      </div>
    </div>
  );
}
