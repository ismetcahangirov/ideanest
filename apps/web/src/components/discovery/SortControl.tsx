'use client';

import { Field, Select } from '@ideanest/ui';
import { SORTS, isSort, type DiscoverySort } from '../../lib/discovery/vocabulary';

/**
 * The order the feed comes back in.
 *
 * A NATIVE `<select>`, which docs/ui-kit.md §7.13 calls a decision rather than a
 * shortcut: a hand-built listbox has to re-implement type-ahead, Home/End,
 * PageUp/PageDown, the announcement contract, and the platform wheel picker on
 * iOS and Android, and it always gets one of them wrong. There are five options
 * and no rich rows here, so there is nothing a custom control would buy.
 *
 * FIVE OPTIONS, NOT SEVEN. §4.3 lists seven orders and the service declares all
 * of them, but `relevance` (#44) and `near_me` (#47) are refused by every
 * implementation that exists — asking for one is answered
 * `400 DISCOVERY_OPTION_UNSUPPORTED` naming the issue. Offering an order that
 * empties the page is worse than not offering it.
 *
 * NO PLACEHOLDER. There is always an order in force — the service defaults to
 * `newest` when nothing is sent — so "nothing chosen yet" is not a state this
 * control can be in, and a disabled empty option would be a lie about that.
 */

export interface SortControlProps {
  sort: DiscoverySort;
  onChange: (sort: DiscoverySort) => void;
}

export function SortControl({ sort, onChange }: SortControlProps) {
  return (
    <Field label="Sort by" className="w-full sm:w-56">
      <Select
        value={sort}
        onChange={(event) => {
          const value = event.target.value;
          // The vocabulary is closed and the service refuses anything outside
          // it, so a value that is not one of the five is never sent.
          if (isSort(value)) onChange(value);
        }}
      >
        {SORTS.map((option) => (
          <option key={option.value} value={option.value}>
            {option.label}
          </option>
        ))}
      </Select>
    </Field>
  );
}
