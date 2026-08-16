import { afterEach, describe, expect, it, vi } from 'vitest';
import { useState } from 'react';
import { cleanup, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Combobox, type ComboboxOption } from './Combobox';

/**
 * The combobox's keyboard and ARIA contract.
 *
 * Appearance is reviewed in Storybook (CLAUDE.md §3) and pinned by the markup
 * snapshots; what is here is the part a screenshot cannot see and a reviewer
 * cannot check by eye — where DOM focus is, what `aria-activedescendant` points
 * at, and what each key does to both.
 *
 * The interesting assertion in almost every case is the SECOND one: that the
 * input's value did not change. A combobox that rewrites what was typed as the
 * reader arrows past a row is the failure this control is built to avoid, and
 * it is invisible unless it is asserted.
 */

const OPTIONS: readonly ComboboxOption[] = [
  { id: 'option-one', label: 'Oyun gecəsi dəsti', kind: 'Campaign' },
  { id: 'option-two', label: 'Games', kind: 'Category' },
  { id: 'option-three', label: 'handmade', kind: 'Tag' },
];

function Harness({
  options = OPTIONS,
  onSelect = () => {},
  onSubmit = () => {},
  initialOpen = true,
  message,
}: {
  options?: readonly ComboboxOption[];
  onSelect?: (option: ComboboxOption) => void;
  onSubmit?: (value: string) => void;
  initialOpen?: boolean;
  message?: string;
}) {
  const [value, setValue] = useState('oyun');
  const [open, setOpen] = useState(initialOpen);

  return (
    <>
      <Combobox
        value={value}
        onValueChange={setValue}
        options={options}
        open={open}
        onOpenChange={setOpen}
        onSelect={onSelect}
        onSubmit={onSubmit}
        listboxLabel="Suggestions"
        aria-label="Search campaigns"
        message={message}
      />
      {/* Somewhere for Tab to go, so "Tab leaves" is a real assertion. */}
      <button type="button">After</button>
    </>
  );
}

const input = (): HTMLInputElement => screen.getByRole('combobox');

/** The option `aria-activedescendant` currently names, or null. */
function activeOption(): HTMLElement | null {
  const id = input().getAttribute('aria-activedescendant');
  return id === null ? null : document.getElementById(id);
}

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe('Combobox', () => {
  it('is a combobox with a listbox popup, and says so', () => {
    render(<Harness />);

    expect(input()).toHaveAttribute('aria-expanded', 'true');
    expect(input()).toHaveAttribute('aria-autocomplete', 'list');

    const listbox = screen.getByRole('listbox', { name: 'Suggestions' });
    expect(input()).toHaveAttribute('aria-controls', listbox.id);
    expect(screen.getAllByRole('option')).toHaveLength(3);
  });

  it('reports the popup as collapsed when there is nothing in it', () => {
    render(<Harness options={[]} />);

    expect(input()).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
  });

  it('shows a message with no options, because silence reads as a broken control', () => {
    render(<Harness options={[]} message="No suggestions for “oyun”." />);

    expect(input()).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByText('No suggestions for “oyun”.')).toBeInTheDocument();
  });

  it('says each option kind as text, not by colour or icon alone', () => {
    render(<Harness />);

    // The accessible name of an option is computed from its contents, so the
    // kind is part of what is announced rather than a visual aside.
    expect(screen.getByRole('option', { name: /Games\s*Category/ })).toBeInTheDocument();
    expect(screen.getByRole('option', { name: /handmade\s*Tag/ })).toBeInTheDocument();
  });

  /* -----------------------------------------------------------------------
   * The keyboard walk
   * -------------------------------------------------------------------- */

  it('walks the options with Down and Up, keeping DOM focus on the input', async () => {
    const user = userEvent.setup();
    render(<Harness />);
    await user.click(input());

    await user.keyboard('{ArrowDown}');
    expect(activeOption()).toHaveTextContent('Oyun gecəsi dəsti');
    expect(input()).toHaveFocus();
    expect(input()).toHaveValue('oyun');

    await user.keyboard('{ArrowDown}');
    expect(activeOption()).toHaveTextContent('Games');

    await user.keyboard('{ArrowUp}');
    expect(activeOption()).toHaveTextContent('Oyun gecəsi dəsti');

    // Focus never entered the list, and the typed value was never rewritten —
    // there is no inline completion in this control.
    expect(input()).toHaveFocus();
    expect(input()).toHaveValue('oyun');
  });

  it('marks exactly one option selected, and follows the active one', async () => {
    const user = userEvent.setup();
    render(<Harness />);
    await user.click(input());

    await user.keyboard('{ArrowDown}{ArrowDown}');

    const selected = screen.getAllByRole('option').filter(
      (option) => option.getAttribute('aria-selected') === 'true',
    );
    expect(selected).toHaveLength(1);
    expect(selected[0]).toBe(activeOption());
  });

  it('wraps at both ends rather than stopping', async () => {
    const user = userEvent.setup();
    render(<Harness />);
    await user.click(input());

    // Down past the last option lands on the first.
    await user.keyboard('{ArrowDown}{ArrowDown}{ArrowDown}');
    expect(activeOption()).toHaveTextContent('handmade');
    await user.keyboard('{ArrowDown}');
    expect(activeOption()).toHaveTextContent('Oyun gecəsi dəsti');

    // Up from the first lands on the last.
    await user.keyboard('{ArrowUp}');
    expect(activeOption()).toHaveTextContent('handmade');
  });

  it('takes Home and End to the ends of the list once the list is being walked', async () => {
    const user = userEvent.setup();
    render(<Harness />);
    await user.click(input());

    await user.keyboard('{ArrowDown}{End}');
    expect(activeOption()).toHaveTextContent('handmade');

    await user.keyboard('{Home}');
    expect(activeOption()).toHaveTextContent('Oyun gecəsi dəsti');
  });

  it('leaves Home and End to the caret until an option is active', async () => {
    const user = userEvent.setup();
    render(<Harness />);
    await user.click(input());

    // Nothing is active, so these are the text-editing shortcuts they are in
    // every other field. A reader fixing the first letter of what they typed
    // must not be thrown into a dropdown to do it.
    await user.keyboard('{Home}');
    expect(input()).not.toHaveAttribute('aria-activedescendant');
    expect(input().selectionStart).toBe(0);

    await user.keyboard('{End}');
    expect(input()).not.toHaveAttribute('aria-activedescendant');
    expect(input().selectionStart).toBe('oyun'.length);
  });

  it('selects the active option on Enter, and never both selects and submits', async () => {
    const onSelect = vi.fn();
    const onSubmit = vi.fn();
    const user = userEvent.setup();
    render(<Harness onSelect={onSelect} onSubmit={onSubmit} />);
    await user.click(input());

    await user.keyboard('{ArrowDown}{ArrowDown}{Enter}');

    expect(onSelect).toHaveBeenCalledTimes(1);
    expect(onSelect.mock.calls[0]?.[0]).toMatchObject({ label: 'Games' });
    expect(onSubmit).not.toHaveBeenCalled();
    expect(input()).toHaveAttribute('aria-expanded', 'false');
  });

  it('submits what was typed on Enter when no option is active', async () => {
    const onSelect = vi.fn();
    const onSubmit = vi.fn();
    const user = userEvent.setup();
    render(<Harness onSelect={onSelect} onSubmit={onSubmit} />);
    await user.click(input());

    await user.keyboard('{Enter}');

    expect(onSubmit).toHaveBeenCalledWith('oyun');
    expect(onSelect).not.toHaveBeenCalled();
  });

  it('closes on Escape without losing what was typed or where focus is', async () => {
    const user = userEvent.setup();
    render(<Harness />);
    await user.click(input());

    await user.keyboard('{ArrowDown}{Escape}');

    expect(input()).toHaveAttribute('aria-expanded', 'false');
    expect(input()).not.toHaveAttribute('aria-activedescendant');
    expect(input()).toHaveValue('oyun');
    expect(input()).toHaveFocus();
  });

  it('leaves on Tab without selecting the active option', async () => {
    const onSelect = vi.fn();
    const user = userEvent.setup();
    render(<Harness onSelect={onSelect} />);
    await user.click(input());

    await user.keyboard('{ArrowDown}{Tab}');

    expect(onSelect).not.toHaveBeenCalled();
    expect(screen.getByRole('button', { name: 'After' })).toHaveFocus();
    expect(input()).toHaveAttribute('aria-expanded', 'false');
  });

  it('opens the list from a closed one on Down', async () => {
    const user = userEvent.setup();
    render(<Harness initialOpen={false} />);
    await user.click(input());

    expect(input()).toHaveAttribute('aria-expanded', 'false');
    await user.keyboard('{ArrowDown}');

    expect(input()).toHaveAttribute('aria-expanded', 'true');
    expect(activeOption()).toHaveTextContent('Oyun gecəsi dəsti');
  });

  it('drops the active option when the typed value changes', async () => {
    const user = userEvent.setup();
    render(<Harness />);
    await user.click(input());

    await user.keyboard('{ArrowDown}');
    expect(activeOption()).not.toBeNull();

    await user.keyboard('a');
    // The list is about to be replaced by one for a different fragment, and an
    // active row held across that is a row pointing at a campaign the reader
    // never highlighted.
    expect(input()).not.toHaveAttribute('aria-activedescendant');
  });

  it('selects on a pointer press', async () => {
    const onSelect = vi.fn();
    const user = userEvent.setup();
    render(<Harness onSelect={onSelect} />);

    await user.click(screen.getByRole('option', { name: /handmade/ }));

    expect(onSelect.mock.calls[0]?.[0]).toMatchObject({ label: 'handmade' });
  });

  /* -----------------------------------------------------------------------
   * The announcement
   * -------------------------------------------------------------------- */

  it('announces the number of suggestions politely', () => {
    const { container } = render(<Harness />);

    const live = container.querySelector('[aria-live="polite"]');
    expect(live).toHaveTextContent('3 suggestions available.');
  });

  it('announces that there are none, because silence reads as broken', () => {
    const { container } = render(<Harness options={[]} message="No suggestions." />);

    expect(container.querySelector('[aria-live="polite"]')).toHaveTextContent('No suggestions.');
  });
});
