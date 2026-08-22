/**
 * §4.3's taxonomy, as `GET /v1/categories` sends it — `CategoryController.CategoryResponse`.
 *
 * ONE MODULE, ONE PLACE, the rule the discovery and project clients already state. The
 * shapes come from the controller; nothing here invents a field.
 *
 * <h2>The taxonomy is data, and this module treats it as data</h2>
 *
 * §4.3 is explicit that the fifteen categories and their hundred-odd subcategories must be
 * editable without a deployment. So there is no list of names in this file and no
 * `CATEGORIES` constant anywhere in the application: the browse pages read the tree at
 * request time, and a category added by an administrator has a landing page without anybody
 * shipping one.
 *
 * <h2>`name`, and not `nameAz` or `nameEn`</h2>
 *
 * The controller resolves the reader's language and puts the answer in `name` — resolved
 * locale, then Azerbaijani, then the slug. The other fields are interim and the controller
 * says so. A client that picked one of them would be choosing a language on the reader's
 * behalf and would then have to be changed again when §21.1's routing lands.
 */

export interface Subcategory {
  readonly id: string;
  readonly slug: string;
  /** In the reader's language, already resolved by the service. */
  readonly name: string;
}

export interface Category extends Subcategory {
  /**
   * Nested rather than a second request, and the controller explains why: a dependent
   * `<select>` that waits for a round trip to populate is a select that appears empty for a
   * moment.
   */
  readonly subcategories: readonly Subcategory[];
}

/* -------------------------------------------------------------------------
 * The browse URLs — §4.13 WS-05
 * ---------------------------------------------------------------------- */

/**
 * A category's landing page.
 *
 * `/categories/{slug}` rather than `/discover?category={slug}`, and that is the whole point
 * of WS-05: a crawler cannot operate a filter. `lib/seo/sitemap/entries.ts` said so before
 * these pages existed — robots.txt disallows `/discover?` wholesale, so the only URL that
 * reached a category was one no crawler is allowed to fetch.
 *
 * Slugs are lower case throughout the schema and the service folds them, but they are still
 * encoded: a slug is data, and a taxonomy editable without a deployment is a taxonomy that
 * can acquire a character somebody did not expect.
 */
export function categoryPath(slug: string): string {
  return `/categories/${encodeURIComponent(slug)}`;
}

export function subcategoryPath(categorySlug: string, subcategorySlug: string): string {
  return `${categoryPath(categorySlug)}/${encodeURIComponent(subcategorySlug)}`;
}

/* -------------------------------------------------------------------------
 * Reading the tree
 * ---------------------------------------------------------------------- */

/** The category with this slug, or `null`. Case-insensitive, as the service is. */
export function findCategory(
  tree: readonly Category[],
  slug: string,
): Category | null {
  const wanted = slug.toLowerCase();
  return tree.find((category) => category.slug.toLowerCase() === wanted) ?? null;
}

/**
 * A subcategory within its own parent, or `null`.
 *
 * SCOPED TO THE PARENT, never searched across the tree. A subcategory slug is only unique
 * within its category (`lib/discovery/suggest.ts` says the same thing about suggestion ids),
 * so a global lookup would answer `/categories/games/prints` with the Crafts subcategory and
 * render a page whose breadcrumb contradicts its own URL.
 */
export function findSubcategory(
  category: Category,
  slug: string,
): Subcategory | null {
  const wanted = slug.toLowerCase();
  return category.subcategories.find((entry) => entry.slug.toLowerCase() === wanted) ?? null;
}
