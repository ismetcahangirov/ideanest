import type { Meta, StoryObj } from '@storybook/react-vite';
import { Search } from 'lucide-react';
import { Field } from './Field';
import { TextInput } from './TextInput';

const meta = {
  title: 'Form/TextInput',
  component: TextInput,
  parameters: {
    layout: 'padded',
    docs: {
      description: {
        component:
          'Fill is `--surface-3`; the placeholder is `--text-tertiary`, never `--text-disabled`. Focus is the global lime ring, not a component-local one.',
      },
    },
  },
  args: { placeholder: 'you@example.com' },
  decorators: [
    (Story) => (
      <div className="w-[360px]">
        <Story />
      </div>
    ),
  ],
} satisfies Meta<typeof TextInput>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};
export const Small: Story = { args: { size: 'sm' } };
export const Large: Story = { args: { size: 'lg' } };
export const Disabled: Story = { args: { disabled: true, value: 'locked@example.com' } };
export const Invalid: Story = { args: { invalid: true, value: 'not-an-address', readOnly: true } };

export const WithAdornments: Story = {
  args: { placeholder: 'Search campaigns', leading: <Search /> },
};

/** Money is a string end to end; the currency sits outside the value. */
export const InAField: Story = {
  render: () => (
    <Field label="Pledge amount" hint="Charged only if the campaign funds.">
      <TextInput inputMode="decimal" placeholder="0.00" leading={<span>€</span>} />
    </Field>
  ),
};
