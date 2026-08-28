import type { Metadata } from 'next';
import { getTranslations } from 'next-intl/server';
import { ConsoleIndex } from '../../../components/admin/ConsoleIndex';
import { privatePageMetadata } from '../../../lib/seo/metadata';
import { consoleIndexCopy } from '../../../lib/i18n/shell-copy.server';

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
export async function generateMetadata(): Promise<Metadata> {
  const t = await getTranslations('admin.index');
  return privatePageMetadata({ title: t('title'), description: t('metaDescription') });
}

export default async function AdminConsolePage() {
  return <ConsoleIndex copy={await consoleIndexCopy()} />;
}
