import type { Meta, StoryObj } from '@storybook/react-vite';
import { Search } from 'lucide-react';
import { useState } from 'react';
import { Field } from '../form/Field';
import { Combobox, type ComboboxOption } from './Combobox';

const meta = {
  title: 'Overlays/Combobox',
  component: Combobox,
  parameters: {
    layout: 'padded',
    docs: {
      description: {
        component:
          'A text input with a listbox popup. DOM focus never leaves the input — the active row is named by `aria-activedescendant`. There is no inline completion: the typed value is what a plain Enter submits. Each row says its kind as text, because colour and icon never carry meaning alone (ui-kit §9.2).',
      },
    },
  },
  args: {
    value: '',
    onValueChange: () => {},
    options: [],
    open: false,
    onOpenChange: () => {},
    onSelect: () => {},
    listboxLabel: 'Suggestions',
  },
  decorators: [
    (Story) => (
      <div className="h-[320px] w-[420px]">
        <Story />
      </div>
    ),
  ],
} satisfies Meta<typeof Combobox>;

export default meta;
type Story = StoryObj<typeof meta>;

/** The four kinds a search box suggests, each saying which it is. */
const SUGGESTIONS: readonly ComboboxOption[] = [
  { id: 'suggestion-campaign', label: 'Oyun gecəsi dəsti', kind: 'Campaign' },
  { id: 'suggestion-category', label: 'Games', kind: 'Category' },
  { id: 'suggestion-subcategory', label: 'Tabletop games', kind: 'Subcategory' },
  { id: 'suggestion-tag', label: 'handmade', kind: 'Tag' },
];

export const Closed: Story = {
  args: { value: 'oyun', placeholder: 'Search campaigns', leading: <Search /> },
};

export const Open: Story = {
  args: {
    value: 'oyun',
    open: true,
    options: SUGGESTIONS,
    placeholder: 'Search campaigns',
    leading: <Search />,
  },
};

/**
 * Type, then arrow. The active row highlights and `aria-activedescendant`
 * follows it, while the caret and the typed text stay where they were — which
 * is the behaviour to check by hand, because no snapshot can see a caret.
 */
export const Interactive: Story = {
  render: (args) => {
    const [value, setValue] = useState('');
    const [open, setOpen] = useState(false);
    const matches = SUGGESTIONS.filter((option) =>
      option.label.toLowerCase().includes(value.toLowerCase()),
    );

    return (
      <Combobox
        {...args}
        value={value}
        onValueChange={(next) => {
          setValue(next);
          setOpen(next.length >= 2);
        }}
        open={open}
        onOpenChange={setOpen}
        options={value.length >= 2 ? matches : []}
        message={value.length >= 2 && matches.length === 0 ? 'No suggestions.' : undefined}
        leading={<Search />}
        placeholder="Search campaigns"
      />
    );
  },
};

/** Nothing matched. Silence would read as a control that had broken. */
export const NoSuggestions: Story = {
  args: {
    value: 'qwertyuiop',
    open: true,
    options: [],
    message: 'No suggestions for “qwertyuiop”. Press Enter to search anyway.',
    leading: <Search />,
  },
};

export const Loading: Story = {
  args: { value: 'oyu', open: true, options: [], message: 'Looking for suggestions', leading: <Search /> },
};

/**
 * A refusal is the service's own words. The input still submits — a suggestion
 * list that cannot be built is not a search box that has stopped working.
 */
export const Failed: Story = {
  args: {
    value: 'oyun',
    open: true,
    options: [],
    message: 'Suggestions are unavailable. Press Enter to search for what you typed.',
    leading: <Search />,
  },
};

export const InAField: Story = {
  render: (args) => (
    <Field label="Search campaigns" hint="Titles, categories, and tags.">
      <Combobox {...args} value="oyun" open options={SUGGESTIONS} leading={<Search />} />
    </Field>
  ),
};
