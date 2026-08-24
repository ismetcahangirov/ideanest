import { authorizedFetch } from '../api/client';
import { errorFrom } from '../api/problem';

/**
 * §4.11's AD-08: categories, subcategories and tags — §4.3, issue #309.
 *
 * <h2>A handle is permanent and a name is not</h2>
 *
 * The slug is in the public URL of every campaign filed under a category, and the platform
 * has no redirect table — so renaming one breaks every link anybody has ever shared, with no
 * way to find out how many. No request body here carries one after creation, which is the
 * cheapest possible way to make that unspellable.
 *
 * <h2>Nothing here deletes</h2>
 *
 * Not for want of a verb. `projects.category_id` references these rows, so a delete either
 * fails on the constraint or takes campaigns with it, and retiring a category — hiding it
 * from the editor while leaving the campaigns filed under it — needs a column V6 does not
 * have. The screen says that rather than offering a button that returns a 409.
 */

export interface TaxonomyEntry {
  id: string;
  slug: string;
  /** The two names V6 keeps on the row itself. */
  nameAz: string;
  nameEn: string;
  sortOrder: number;
  /** §21.1's other locales, keyed by code. Absent locales fall back to `nameEn`. */
  translations: Record<string, string>;
}

export interface TaxonomySubcategory extends TaxonomyEntry {
  parentId: string;
}

/** A category and the subcategories filed under it. */
export interface TaxonomyBranch {
  category: TaxonomyEntry;
  subcategories: TaxonomySubcategory[];
}

export interface TaxonomyTree {
  branches: TaxonomyBranch[];
}

/**
 * One tag and how heavily it is used.
 *
 * Read-only. §4.3 gives tags no editorial vocabulary — creators type them — so renaming one
 * would be rewriting what somebody said about their own campaign. `usageCount` is the whole
 * reason the list is on the screen: it is the input to "should this be a category".
 */
export interface TaxonomyTag {
  id: string;
  slug: string;
  label: string;
  usageCount: number;
}

export interface TaxonomyTagList {
  tags: TaxonomyTag[];
}

/** The whole tree, with every translation. Unpaged — a tree that pages cannot be reordered. */
export async function readTaxonomy(signal?: AbortSignal): Promise<TaxonomyTree> {
  const response = await authorizedFetch('/v1/admin/taxonomy', { signal });
  if (!response.ok) throw await errorFrom(response);

  return (await response.json()) as TaxonomyTree;
}

/** The tags creators have used, most-used first. */
export async function readTaxonomyTags(signal?: AbortSignal): Promise<TaxonomyTagList> {
  const response = await authorizedFetch('/v1/admin/taxonomy/tags', { signal });
  if (!response.ok) throw await errorFrom(response);

  return (await response.json()) as TaxonomyTagList;
}

export interface NewTaxonomyEntry {
  readonly slug: string;
  readonly nameAz: string;
  readonly nameEn: string;
  readonly sortOrder: number;
}

/** Adds a category. */
export async function createCategory(
  entry: NewTaxonomyEntry,
  signal?: AbortSignal,
): Promise<TaxonomyEntry> {
  const response = await authorizedFetch('/v1/admin/taxonomy/categories', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(entry),
    signal,
  });
  if (!response.ok) throw await errorFrom(response);

  return (await response.json()) as TaxonomyEntry;
}

/** Adds a subcategory. The parent is permanent — moving one re-files every campaign under it. */
export async function createSubcategory(
  categoryId: string,
  entry: NewTaxonomyEntry,
  signal?: AbortSignal,
): Promise<TaxonomySubcategory> {
  const response = await authorizedFetch(
    `/v1/admin/taxonomy/categories/${encodeURIComponent(categoryId)}/subcategories`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(entry),
      signal,
    },
  );
  if (!response.ok) throw await errorFrom(response);

  return (await response.json()) as TaxonomySubcategory;
}

export interface TaxonomyEdit {
  readonly nameAz: string;
  readonly nameEn: string;
  readonly sortOrder: number;
}

/**
 * Renames an entry and moves it.
 *
 * Both names travel, always. A per-field patch produces a category whose Azerbaijani name is
 * the new one and whose English is the old, the first time somebody is interrupted.
 */
export async function editTaxonomyEntry(
  kind: 'categories' | 'subcategories',
  id: string,
  edit: TaxonomyEdit,
  signal?: AbortSignal,
): Promise<TaxonomyEntry> {
  const response = await authorizedFetch(`/v1/admin/taxonomy/${kind}/${encodeURIComponent(id)}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(edit),
    signal,
  });
  if (!response.ok) throw await errorFrom(response);

  return (await response.json()) as TaxonomyEntry;
}

/** Writes an entry's name in one locale. An upsert: adding and correcting are one intent. */
export async function translateTaxonomyEntry(
  kind: 'categories' | 'subcategories',
  id: string,
  locale: string,
  name: string,
  signal?: AbortSignal,
): Promise<void> {
  const response = await authorizedFetch(
    `/v1/admin/taxonomy/${kind}/${encodeURIComponent(id)}/translations/${encodeURIComponent(locale)}`,
    {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name }),
      signal,
    },
  );
  if (!response.ok) throw await errorFrom(response);
}
