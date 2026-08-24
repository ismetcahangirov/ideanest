'use client';

import { useState } from 'react';
import {
  EmptyState,
  Field,
  InlineAlert,
  Pill,
  Skeleton,
  SkeletonGroup,
  TextInput,
} from '@ideanest/ui';
import {
  createCategory,
  createSubcategory,
  editTaxonomyEntry,
  readTaxonomy,
  readTaxonomyTags,
  translateTaxonomyEntry,
  type TaxonomyBranch,
  type TaxonomyEntry,
} from '../../lib/admin/taxonomy';
import { consoleMessageFor } from '../../lib/admin/refusals';
import { ConsoleRefusal } from './ConsoleRefusal';
import { useConsoleResource } from './useConsoleResource';

const SUBJECT = 'the taxonomy';

/**
 * §4.11's AD-08: categories, subcategories and tags with a translation per locale — issue #309.
 *
 * <h2>What was blocking this, and what it turned out to be</h2>
 *
 * #309 said "§4.3 requires the taxonomy be editable without a deployment, and no API exposes
 * it". The tables had existed since V6 and V11; what did not exist was any way to write to
 * them — the entities said so in as many words, "nothing in the application creates a category;
 * the migration does". So this is the screen half of a feature whose schema was already there.
 *
 * <h2>Handles cannot be changed and the screen does not offer to</h2>
 *
 * A slug is in the public URL of every campaign filed under a category, and the platform has no
 * redirect table — renaming one breaks every link anybody has shared, silently, with no way to
 * count them. The edit form has no slug field at all, which is cheaper than a warning somebody
 * clicks through.
 *
 * <h2>Nothing here deletes, and it says why</h2>
 *
 * `projects.category_id` references these rows. A delete either fails on the constraint or
 * takes campaigns with it, and retiring a category — hiding it from the editor while leaving
 * the campaigns filed under it — needs a column V6 does not have. Saying that is better than a
 * button that returns a 409.
 */
export function TaxonomyManager() {
  const tree = useConsoleResource((signal) => readTaxonomy(signal), SUBJECT, []);
  const tags = useConsoleResource((signal) => readTaxonomyTags(signal), 'the tag list', []);

  const [slug, setSlug] = useState('');
  const [nameAz, setNameAz] = useState('');
  const [nameEn, setNameEn] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  if (tree.status === 'signed-out' || tree.status === 'forbidden') {
    return <ConsoleRefusal status={tree.status} subject={SUBJECT} />;
  }

  async function act(work: () => Promise<unknown>): Promise<void> {
    setBusy(true);
    setError(null);
    try {
      await work();
      tree.reload();
    } catch (cause) {
      setError(consoleMessageFor(cause, SUBJECT));
    } finally {
      setBusy(false);
    }
  }

  async function addCategory(event: React.FormEvent): Promise<void> {
    event.preventDefault();
    if (slug.trim() === '' || nameAz.trim() === '' || nameEn.trim() === '') return;

    await act(async () => {
      await createCategory({
        slug: slug.trim(),
        nameAz: nameAz.trim(),
        nameEn: nameEn.trim(),
        sortOrder: (tree.data?.branches.length ?? 0) * 10,
      });
      setSlug('');
      setNameAz('');
      setNameEn('');
    });
  }

  return (
    <div className="flex flex-col gap-10">
      <InlineAlert variant="info" title="Handles are permanent and nothing here deletes">
        A handle is in the public URL of every campaign filed under it, and the platform has no
        redirect table — so it cannot be changed after the entry is created. Nothing can be
        removed either: campaigns reference these rows, and retiring one needs a column the
        schema does not have yet.
      </InlineAlert>

      <section aria-labelledby="tree-heading">
        <h2 id="tree-heading" className="text-lg font-medium tracking-[-0.02em] text-white">
          Categories
        </h2>

        {tree.status === 'loading' && (
          <SkeletonGroup label="Loading the taxonomy" className="mt-4">
            <div className="space-y-3">
              {[0, 1, 2].map((row) => (
                <div key={row} className="rounded-lg border border-white/8 bg-surface-1 p-4">
                  <Skeleton height="1rem" width="35%" />
                </div>
              ))}
            </div>
          </SkeletonGroup>
        )}

        {tree.status === 'failed' && (
          <>
            <InlineAlert variant="danger" title="Something went wrong" className="mt-4">
              {tree.error}
            </InlineAlert>
            <Pill variant="ghost" size="sm" className="mt-4" onClick={tree.reload}>
              Try again
            </Pill>
          </>
        )}

        {tree.status === 'ready' && tree.data !== null && tree.data.branches.length === 0 && (
          <EmptyState
            className="mt-4"
            variant="empty"
            title="The taxonomy is empty"
            description="No campaign can be filed anywhere until there is a category. Add one below."
          />
        )}

        {tree.status === 'ready' && tree.data !== null && tree.data.branches.length > 0 && (
          <ul className="mt-4 flex list-none flex-col gap-3">
            {tree.data.branches.map((branch) => (
              <Branch key={branch.category.id} branch={branch} busy={busy} onAct={act} />
            ))}
          </ul>
        )}

        {error && (
          <InlineAlert variant="danger" title="That did not work" className="mt-4">
            {error}
          </InlineAlert>
        )}
      </section>

      <section aria-labelledby="add-category-heading">
        <h2 id="add-category-heading" className="text-lg font-medium tracking-[-0.02em] text-white">
          Add a category
        </h2>

        <form onSubmit={(event) => void addCategory(event)} className="mt-4 flex flex-wrap items-end gap-3">
          <Field label="Handle" hint="Permanent. Lower case and hyphenated." className="min-w-[200px]">
            <TextInput value={slug} onChange={(event) => setSlug(event.target.value)} placeholder="games" />
          </Field>
          <Field label="Azerbaijani" className="min-w-[180px]">
            <TextInput value={nameAz} onChange={(event) => setNameAz(event.target.value)} />
          </Field>
          <Field label="English" className="min-w-[180px]">
            <TextInput value={nameEn} onChange={(event) => setNameEn(event.target.value)} />
          </Field>
          <Pill type="submit" variant="outline" size="sm" className="mb-1" disabled={busy}>
            Add
          </Pill>
        </form>
      </section>

      <section aria-labelledby="tags-heading">
        <h2 id="tags-heading" className="text-lg font-medium tracking-[-0.02em] text-white">
          Tags
        </h2>
        <p className="mt-2 max-w-[62ch] text-sm text-white/64">
          Read-only. §4.3 gives tags no editorial vocabulary — creators type them — so renaming
          one would be rewriting what somebody said about their own campaign. What this list is
          for is seeing which tags are heavy enough to become a category.
        </p>

        {tags.status === 'ready' && tags.data !== null && (
          <ul className="mt-4 flex list-none flex-wrap gap-2">
            {tags.data.tags.slice(0, 60).map((tag) => (
              <li key={tag.id} className="rounded-md border border-white/8 px-2.5 py-1.5 text-xs text-white/64">
                {tag.label} <span className="text-white/40">{tag.usageCount}</span>
              </li>
            ))}
          </ul>
        )}
        {tags.status === 'ready' && tags.data !== null && tags.data.tags.length === 0 && (
          <p className="mt-4 text-sm text-white/48">No creator has used a tag yet.</p>
        )}
      </section>
    </div>
  );
}

/** One category, its subcategories, and the two things that can be changed about each. */
function Branch({
  branch,
  busy,
  onAct,
}: {
  readonly branch: TaxonomyBranch;
  readonly busy: boolean;
  readonly onAct: (work: () => Promise<unknown>) => Promise<void>;
}) {
  const [open, setOpen] = useState(false);

  return (
    <li className="rounded-lg border border-white/8 bg-surface-1 p-4">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <p className="text-sm text-white">
          {branch.category.nameEn}
          <span className="ml-2 font-mono text-xs text-white/40">{branch.category.slug}</span>
        </p>
        <Pill variant="ghost" size="sm" onClick={() => setOpen(!open)} aria-expanded={open}>
          {open ? 'Done' : 'Edit'}
        </Pill>
      </div>

      <p className="mt-1 text-xs text-white/48">
        {branch.category.nameAz}
        {branch.subcategories.length > 0
          ? ` · ${branch.subcategories.length} subcategories`
          : ' · no subcategories'}
      </p>

      {open && (
        <div className="mt-4 border-t border-white/8 pt-4">
          <EntryEditor entry={branch.category} kind="categories" busy={busy} onAct={onAct} />

          <ul className="mt-4 flex list-none flex-col gap-2 border-l border-white/8 pl-4">
            {branch.subcategories.map((subcategory) => (
              <li key={subcategory.id}>
                <EntryEditor entry={subcategory} kind="subcategories" busy={busy} onAct={onAct} />
              </li>
            ))}
          </ul>

          <NewSubcategory categoryId={branch.category.id} busy={busy} onAct={onAct} />
        </div>
      )}
    </li>
  );
}

/** Renaming and translating one entry. The handle is not here, deliberately. */
function EntryEditor({
  entry,
  kind,
  busy,
  onAct,
}: {
  readonly entry: TaxonomyEntry;
  readonly kind: 'categories' | 'subcategories';
  readonly busy: boolean;
  readonly onAct: (work: () => Promise<unknown>) => Promise<void>;
}) {
  const [nameAz, setNameAz] = useState(entry.nameAz);
  const [nameEn, setNameEn] = useState(entry.nameEn);
  const [locale, setLocale] = useState('ru');
  const [translation, setTranslation] = useState('');

  return (
    <div className="rounded-md border border-white/8 p-3">
      <p className="font-mono text-xs text-white/40">{entry.slug}</p>

      <div className="mt-2 flex flex-wrap items-end gap-2">
        <Field label="Azerbaijani" className="min-w-[160px] flex-1">
          <TextInput value={nameAz} onChange={(event) => setNameAz(event.target.value)} />
        </Field>
        <Field label="English" className="min-w-[160px] flex-1">
          <TextInput value={nameEn} onChange={(event) => setNameEn(event.target.value)} />
        </Field>
        <Pill
          variant="ghost"
          size="sm"
          className="mb-1"
          disabled={busy}
          onClick={() =>
            void onAct(() =>
              editTaxonomyEntry(kind, entry.id, {
                nameAz: nameAz.trim(),
                nameEn: nameEn.trim(),
                sortOrder: entry.sortOrder,
              }),
            )
          }
        >
          Rename
        </Pill>
      </div>

      <div className="mt-2 flex flex-wrap items-end gap-2">
        <Field label="Locale" className="w-[90px]">
          <TextInput value={locale} onChange={(event) => setLocale(event.target.value)} />
        </Field>
        <Field label="Name in that locale" className="min-w-[200px] flex-1">
          <TextInput value={translation} onChange={(event) => setTranslation(event.target.value)} />
        </Field>
        <Pill
          variant="ghost"
          size="sm"
          className="mb-1"
          disabled={busy || translation.trim() === ''}
          onClick={() =>
            void onAct(async () => {
              await translateTaxonomyEntry(kind, entry.id, locale.trim(), translation.trim());
              setTranslation('');
            })
          }
        >
          Translate
        </Pill>
      </div>

      {Object.keys(entry.translations).length > 0 && (
        <p className="mt-2 text-xs text-white/40">
          {Object.entries(entry.translations)
            .map(([code, name]) => `${code}: ${name}`)
            .join(' · ')}
        </p>
      )}
    </div>
  );
}

/** Adding a subcategory. The parent is fixed by where the form is. */
function NewSubcategory({
  categoryId,
  busy,
  onAct,
}: {
  readonly categoryId: string;
  readonly busy: boolean;
  readonly onAct: (work: () => Promise<unknown>) => Promise<void>;
}) {
  const [slug, setSlug] = useState('');
  const [nameAz, setNameAz] = useState('');
  const [nameEn, setNameEn] = useState('');

  return (
    <div className="mt-4 flex flex-wrap items-end gap-2 border-t border-white/8 pt-4">
      <Field label="New subcategory" hint="Handle, permanent." className="min-w-[160px]">
        <TextInput value={slug} onChange={(event) => setSlug(event.target.value)} />
      </Field>
      <Field label="Azerbaijani" className="min-w-[140px]">
        <TextInput value={nameAz} onChange={(event) => setNameAz(event.target.value)} />
      </Field>
      <Field label="English" className="min-w-[140px]">
        <TextInput value={nameEn} onChange={(event) => setNameEn(event.target.value)} />
      </Field>
      <Pill
        variant="ghost"
        size="sm"
        className="mb-1"
        disabled={busy || slug.trim() === '' || nameEn.trim() === ''}
        onClick={() =>
          void onAct(async () => {
            await createSubcategory(categoryId, {
              slug: slug.trim(),
              nameAz: nameAz.trim(),
              nameEn: nameEn.trim(),
              sortOrder: 0,
            });
            setSlug('');
            setNameAz('');
            setNameEn('');
          })
        }
      >
        Add
      </Pill>
    </div>
  );
}
