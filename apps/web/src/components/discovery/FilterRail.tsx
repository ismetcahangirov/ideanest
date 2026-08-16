'use client';

import { useEffect, useState, type ReactNode } from 'react';
import { Checkbox, Field, Pill, TextInput } from '@ideanest/ui';
import { countOf, type DiscoveryFacets, type ValueCount } from '../../lib/discovery/api';
import {
  boundsAreOrdered,
  isValidBound,
  toggleAmountBand,
  toggleCategory,
  toggleCompletion,
  toggleStatus,
  toggleSubcategory,
  toggleTag,
  withAmountRange,
  type DiscoveryFilters,
} from '../../lib/discovery/filters';
import { AMOUNT_BANDS, COMPLETION_BANDS, STATUSES } from '../../lib/discovery/vocabulary';

/**
 * The filter panel: every filter §4.3 lists that `GET /v1/discover` can
 * actually apply.
 *
 * WHAT IS NOT HERE, AND WHY. Location (country, city, proximity),
 * `showOnly=saved`, `showOnly=recommended` and `showOnly=featured` are
 * representable on the service's query object and refused by every
 * implementation of it — there is no location column, no saved-projects table,
 * no ranking, and no curation yet, and each refusal names the issue that owns
 * it (#47, #44, #48). Rendering a control that is answered with
 * `400 DISCOVERY_OPTION_UNSUPPORTED` would be an interface that breaks the
 * first time anybody uses it, so they are absent rather than disabled: a
 * disabled control still says "this exists and you may not have it", which is
 * a different and equally wrong claim. Free-text search is #43/#46 and is not a
 * filter in this rail.
 *
 * A ZERO IS SHOWN, NEVER HIDDEN. The fixed vocabularies answer for all of their
 * values including the empty ones, and the rail renders all of them: a control
 * that disappears when its count reaches zero is a control that moves under the
 * reader's cursor, and a filter whose options come and go is a filter people
 * stop trusting. An empty option is DISABLED and reads "None" rather than "0",
 * so the unavailability is a word and not only a dimming.
 *
 * A COUNT EXCLUDES ITS OWN DIMENSION. That is the service's doing, and it is
 * what makes the panel usable: counted under the category already chosen, every
 * other category would read zero and the only move left would be to clear the
 * filter.
 *
 * KEYBOARD AND STRUCTURE. Real `<fieldset>`/`<legend>` per dimension and real
 * `<input type="checkbox">` throughout, so the rail is one landmark containing
 * named groups rather than a soup of divs — a screen-reader user can jump
 * between the groups, and everything here is reachable and operable with Tab
 * and Space. Nothing is a click handler on a `<div>`.
 *
 * MOTION: none. Discovery's budget is the skeleton-to-content crossfade and
 * nothing else (docs/motion-system.md §5); a panel that animates while somebody
 * is ticking boxes is a panel that is slower to use.
 */

interface FacetCheckboxProps {
  label: string;
  /** Null when the panel has not loaded. An unknown count is never shown as zero. */
  count: number | null;
  checked: boolean;
  onToggle: () => void;
}

function FacetCheckbox({ label, count, checked, onToggle }: FacetCheckboxProps) {
  /*
   * An option is only unavailable if it is BOTH empty and unchosen. A ticked
   * value that now counts zero must stay operable, or the reader is left with a
   * filter they cannot remove from the control that applied it.
   */
  const unavailable = count === 0 && !checked;

  return (
    <li className="flex items-center justify-between gap-3">
      <Checkbox checked={checked} disabled={unavailable} onChange={onToggle} label={label} />
      <span className="shrink-0 text-xs text-white/40 tabular-nums">
        {count === null ? '' : count === 0 ? 'None' : count}
      </span>
    </li>
  );
}

interface GroupProps {
  legend: string;
  children: ReactNode;
}

function Group({ legend, children }: GroupProps) {
  return (
    <fieldset className="border-t border-white/8 pt-5">
      <legend className="mb-3 text-sm font-medium text-white">{legend}</legend>
      {children}
    </fieldset>
  );
}

/* -------------------------------------------------------------------------
 * The custom money range
 * ---------------------------------------------------------------------- */

interface RangeFieldsProps {
  dimension: 'goal' | 'raised';
  legend: string;
  min: string | null;
  max: string | null;
  onApply: (range: { min: string | null; max: string | null }) => void;
}

/**
 * Two boxes and an apply control, deliberately not a live filter.
 *
 * A range applied per keystroke sends a request for "2", "25", "250" and "2500"
 * on the way to 25,000 — four feeds nobody asked for, and the reader watches
 * the results thrash while they are still typing. It also cannot be validated:
 * a minimum above its maximum is only wrong once both have been typed, and the
 * service answers that pair with a 400.
 *
 * The bounds are checked with `decimal.js` before they are applied, because
 * they are money and money never goes near floating point (CLAUDE.md §3).
 */
function RangeFields({ dimension, legend, min, max, onApply }: RangeFieldsProps) {
  const [from, setFrom] = useState(min ?? '');
  const [to, setTo] = useState(max ?? '');
  const [error, setError] = useState<string | null>(null);

  // The URL is the state, so a range removed by its chip has to come back out
  // of these boxes too — otherwise the panel disagrees with the feed.
  useEffect(() => setFrom(min ?? ''), [min]);
  useEffect(() => setTo(max ?? ''), [max]);

  function apply(): void {
    const lower = from.trim() === '' ? null : from.trim();
    const upper = to.trim() === '' ? null : to.trim();

    if (!isValidBound(lower) || !isValidBound(upper)) {
      setError('Enter an amount in digits, for example 2500 or 2500.00.');
      return;
    }
    if (!boundsAreOrdered(lower, upper)) {
      setError('The lowest amount must not be more than the highest.');
      return;
    }

    setError(null);
    onApply({ min: lower, max: upper });
  }

  return (
    <div className="mt-4 flex flex-col gap-3">
      <p className="text-[13px] text-white/64">Or a custom range, in AZN.</p>

      {/*
        The labels name their dimension. Two fields called "Lowest" on one page
        are two controls with the same accessible name, and a screen-reader
        user tabbing into the second has no way to tell which range they are in.
      */}
      <div className="flex gap-3">
        <Field label={`Lowest ${legend.toLowerCase()}`} className="flex-1">
          <TextInput
            inputMode="decimal"
            autoComplete="off"
            value={from}
            onChange={(event) => setFrom(event.target.value)}
          />
        </Field>
        <Field label={`Highest ${legend.toLowerCase()}`} className="flex-1">
          <TextInput
            inputMode="decimal"
            autoComplete="off"
            value={to}
            onChange={(event) => setTo(event.target.value)}
          />
        </Field>
      </div>

      {error !== null && (
        // Announced on insertion rather than only coloured. A red border says
        // nothing to a screen reader (docs/ui-kit.md §7.13).
        <p role="alert" className="text-[13px] text-danger">
          {error}
        </p>
      )}

      <div>
        <Pill
          size="sm"
          variant="ghost"
          onClick={apply}
          aria-label={`Apply the custom ${legend.toLowerCase()} range`}
          data-range={dimension}
        >
          Apply range
        </Pill>
      </div>
    </div>
  );
}

/* -------------------------------------------------------------------------
 * The rail
 * ---------------------------------------------------------------------- */

export interface FilterRailProps {
  filters: DiscoveryFilters;
  facets: DiscoveryFacets | null;
  onChange: (next: DiscoveryFilters) => void;
}

export function FilterRail({ filters, facets, onChange }: FilterRailProps) {
  /**
   * The count for one value, or null while the panel has not loaded.
   *
   * NULL IS NOT ZERO. Zero is what marks an option the reader cannot use, and
   * showing an unknown count as zero would disable half the rail every time the
   * panel was slow.
   */
  const countFor = (source: readonly ValueCount[] | undefined, value: string): number | null =>
    facets === null ? null : countOf(source, value);

  return (
    <form
      aria-label="Filters"
      className="flex flex-col gap-5"
      onSubmit={(event) => {
        // Nothing here submits: every control applies on change, and the range
        // has its own button. Enter in a text box must not reload the page.
        event.preventDefault();
      }}
    >
      <Group legend="Status">
        <ul className="flex flex-col gap-2.5">
          {STATUSES.map((status) => (
            <FacetCheckbox
              key={status.value}
              label={status.label}
              count={countFor(facets?.status, status.value)}
              checked={filters.statuses.includes(status.value)}
              onToggle={() => onChange(toggleStatus(filters, status.value))}
            />
          ))}
        </ul>
      </Group>

      <Group legend="Category">
        <ul className="flex flex-col gap-2.5">
          {(facets?.categories ?? []).map((category) => {
            const chosen = filters.categories.includes(category.slug);
            /*
             * Subcategories appear under a category that is TICKED, or under one
             * whose child is already selected — a link may name a subcategory
             * without its parent, and the two filters are independent
             * server-side. Rendering all hundred at once would be a rail nobody
             * can read; hiding a selected one would be a filter with no visible
             * control.
             */
            const childSelected = category.subcategories.some((sub) =>
              filters.subcategories.includes(sub.slug),
            );

            return (
              <li key={category.slug} className="flex flex-col gap-2.5">
                <div className="flex items-center justify-between gap-3">
                  <Checkbox
                    checked={chosen}
                    disabled={category.count === 0 && !chosen}
                    onChange={() => onChange(toggleCategory(filters, category.slug))}
                    label={category.name}
                  />
                  <span className="shrink-0 text-xs text-white/40 tabular-nums">
                    {category.count === 0 ? 'None' : category.count}
                  </span>
                </div>

                {(chosen || childSelected) && category.subcategories.length > 0 && (
                  <ul
                    aria-label={`${category.name} subcategories`}
                    className="ml-8 flex flex-col gap-2.5 border-l border-white/8 pl-4"
                  >
                    {category.subcategories.map((subcategory) => (
                      <FacetCheckbox
                        key={subcategory.slug}
                        label={subcategory.name}
                        count={subcategory.count}
                        checked={filters.subcategories.includes(subcategory.slug)}
                        onToggle={() => onChange(toggleSubcategory(filters, subcategory.slug))}
                      />
                    ))}
                  </ul>
                )}
              </li>
            );
          })}
        </ul>
      </Group>

      <Group legend="Completion">
        <ul className="flex flex-col gap-2.5">
          {COMPLETION_BANDS.map((band) => (
            <FacetCheckbox
              key={band.value}
              label={band.label}
              count={countFor(facets?.completion, band.value)}
              checked={filters.completion.includes(band.value)}
              onToggle={() => onChange(toggleCompletion(filters, band.value))}
            />
          ))}
        </ul>
      </Group>

      <Group legend="Goal amount">
        <ul className="flex flex-col gap-2.5">
          {AMOUNT_BANDS.map((band) => (
            <FacetCheckbox
              key={band.value}
              label={band.label}
              count={countFor(facets?.goalAmount, band.value)}
              checked={filters.goal.bands.includes(band.value)}
              onToggle={() => onChange(toggleAmountBand(filters, 'goal', band.value))}
            />
          ))}
        </ul>
        <RangeFields
          dimension="goal"
          legend="Goal amount"
          min={filters.goal.min}
          max={filters.goal.max}
          onApply={(range) => onChange(withAmountRange(filters, 'goal', range))}
        />
      </Group>

      <Group legend="Amount raised">
        <ul className="flex flex-col gap-2.5">
          {AMOUNT_BANDS.map((band) => (
            <FacetCheckbox
              key={band.value}
              label={band.label}
              count={countFor(facets?.amountRaised, band.value)}
              checked={filters.raised.bands.includes(band.value)}
              onToggle={() => onChange(toggleAmountBand(filters, 'raised', band.value))}
            />
          ))}
        </ul>
        <RangeFields
          dimension="raised"
          legend="Amount raised"
          min={filters.raised.min}
          max={filters.raised.max}
          onApply={(range) => onChange(withAmountRange(filters, 'raised', range))}
        />
      </Group>

      <Group legend="Tags">
        {/*
          A FREE VOCABULARY, so only tags with campaigns behind them are listed —
          the whole list is unbounded and is not a thing that can be rendered.
          A tag with nothing behind it is not a filter. Several tags mean EVERY
          one of them, not any, which is the one dimension where adding a value
          narrows rather than widens; the hint says so, because a control that
          behaves differently from every other control beside it has to.
        */}
        {(facets?.tags ?? []).length === 0 ? (
          <p className="text-[13px] text-white/40">
            No tags on the campaigns matching these filters.
          </p>
        ) : (
          <>
            <p className="mb-3 text-[13px] text-white/64">
              Choosing several tags shows only campaigns carrying all of them.
            </p>
            <ul className="flex flex-col gap-2.5">
              {(facets?.tags ?? []).map((tag) => (
                <FacetCheckbox
                  key={tag.slug}
                  label={tag.name}
                  count={tag.count}
                  checked={filters.tags.includes(tag.slug)}
                  onToggle={() => onChange(toggleTag(filters, tag.slug))}
                />
              ))}
            </ul>
          </>
        )}
      </Group>
    </form>
  );
}
