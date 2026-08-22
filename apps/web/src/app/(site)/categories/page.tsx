import type { Metadata } from 'next';
import Link from 'next/link';
import { fetchCategories } from '../../../lib/api/server';
import { categoryPath, subcategoryPath } from '../../../lib/categories/api';
import { publicPageMetadata } from '../../../lib/seo/metadata';
import { categoryPageGraph } from '../../../lib/seo/structured-data/graphs';
import { StructuredData } from '../../../components/seo/StructuredData';

/**
 * `/categories` — the taxonomy's own page, and the index every category landing page hangs
 * from. §4.13 WS-05, issue #265.
 *
 * <h2>It is the crawl path</h2>
 *
 * A hundred subcategory pages are worth building only if something links to them. The header
 * points here, the footer points here, the home page points here, and this page is the one
 * place that lists every category AND every subcategory as an ordinary link. Without it the
 * landing pages would exist in the sitemap and nowhere in the site, which is the arrangement
 * that makes a crawler treat them as orphans.
 *
 * <h2>The list is data, not code</h2>
 *
 * §4.3 requires the taxonomy to be editable without a deployment, so this page renders
 * whatever `GET /v1/categories` answers and holds no list of its own. A category an
 * administrator adds has a page and a link the moment the read revalidates — an hour, for the
 * reason `fetchCategories` gives.
 */

export const metadata: Metadata = publicPageMetadata({
  title: 'Categories',
  description:
    'Every category and subcategory on IdeaNest, each with its own page of campaigns.',
  path: '/categories',
});

export default async function CategoriesPage() {
  const categories = await fetchCategories();

  return (
    <>
      <StructuredData nodes={categoryPageGraph({ trail: [] })} />

      <div className="mx-auto w-full max-w-[1400px] px-5 py-10 sm:px-6">
        <h1 className="text-3xl font-semibold tracking-[-0.035em] text-white sm:text-4xl">
          Categories
        </h1>
        <p className="mt-2 max-w-[60ch] text-white/64">
          Every category on the platform, and the subcategories inside them. Each has a page of
          its own.
        </p>

        {categories === null || categories.length === 0 ? (
          /*
            A REFUSED READ AND AN EMPTY TAXONOMY LOOK THE SAME HERE, and the page does not
            pretend to tell them apart. Either way the feed is reachable and carries
            everything, which is the one useful thing to say.
          */
          <p className="mt-10 max-w-[60ch] text-white/64">
            The categories could not be loaded just now.{' '}
            <Link
              href="/discover"
              className="text-white underline underline-offset-4 hover:text-white/80"
            >
              The feed
            </Link>{' '}
            carries every campaign on the platform and can be filtered by category there.
          </p>
        ) : (
          <div className="mt-12 grid gap-x-10 gap-y-12 sm:grid-cols-2 lg:grid-cols-3">
            {categories.map((category) => (
              <section key={category.id}>
                <h2 className="text-xl font-medium tracking-[-0.02em] text-white">
                  <Link
                    href={categoryPath(category.slug)}
                    className="rounded-sm hover:underline hover:underline-offset-4 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[var(--lime-500)]"
                  >
                    {category.name}
                  </Link>
                </h2>

                {category.subcategories.length > 0 && (
                  <ul className="mt-4 flex list-none flex-col gap-2">
                    {category.subcategories.map((subcategory) => (
                      <li key={subcategory.id}>
                        <Link
                          href={subcategoryPath(category.slug, subcategory.slug)}
                          className="rounded-sm text-[15px] text-white/40 transition-colors duration-150 ease-in-out hover:text-white focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[var(--lime-500)]"
                        >
                          {subcategory.name}
                        </Link>
                      </li>
                    ))}
                  </ul>
                )}
              </section>
            ))}
          </div>
        )}
      </div>
    </>
  );
}
