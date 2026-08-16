'use client';

import { Field, Select } from '@ideanest/ui';
import { isSort, sortsFor, type DiscoverySort } from '../../lib/discovery/vocabulary';

/**
 * The order the feed comes back in.
 *
 * A NATIVE `<select>`, which docs/ui-kit.md §7.13 calls a decision rather than a
 * shortcut: a hand-built listbox has to re-implement type-ahead, Home/End,
 * PageUp/PageDown, the announcement contract, and the platform wheel picker on
 * iOS and Android, and it always gets one of them wrong. There are five options
 * and no rich rows here, so there is nothing a custom control would buy.
 *
 * NOT SEVEN OPTIONS. §4.3 lists seven orders and the service declares all of
 * them, but `relevance` (#44) and `near_me` (#47) are refused by every
 * implementation that exists — asking for one is answered
 * `400 DISCOVERY_OPTION_UNSUPPORTED` naming the issue. Offering an order that
 * empties the page is worse than not offering it.
 *
 * `best_match` IS OFFERED ONLY WHEN THERE IS SOMETHING TO MATCH, and it is
 * selected by default when there is. This control must not lie about the order
 * the reader is looking at, and there are two ways it could:
 *
 *   - by showing "Newest" on a searched feed. An unstated sort resolves to
 *     `best_match` server-side whenever `q` is present, so the feed under a
 *     control reading "Newest" would be ranked by match quality. `parseFilters`
 *     resolves it the same way, which is why `sort` arrives here already
 *     correct.
 *   - by offering "Best match" on an unsearched one. `best_match` with nothing
 *     to rank resolves straight back to `newest`, so the option would appear to
 *     be selectable and then do nothing at all.
 *
 * NO PLACEHOLDER. There is always an order in force, so "nothing chosen yet" is
 * not a state this control can be in and a disabled empty option would be a lie
 * about that.
 */

export interface SortControlProps {
  sort: DiscoverySort;
  /** Whether the feed is a search. Decides whether `best_match` is offered. */
  hasQuery: boolean;
  onChange: (sort: DiscoverySort) => void;
}

export function SortControl({ sort, hasQuery, onChange }: SortControlProps) {
  const options = sortsFor(hasQuery);

  return (
    <Field label="Sort by" className="w-full sm:w-56">
      <Select
        value={sort}
        onChange={(event) => {
          const value = event.target.value;
          // The vocabulary is closed and the service refuses anything outside
          // it, so a value that is not one of them is never sent.
          if (isSort(value)) onChange(value);
        }}
      >
        {options.map((option) => (
          <option key={option.value} value={option.value}>
            {option.label}
          </option>
        ))}
      </Select>
    </Field>
  );
}
