import type { Meta, StoryObj } from '@storybook/react-vite';
import { Field } from './Field';
import { Select } from './Select';
import { SAMPLE_CATEGORIES } from '../../sample-data';

const options = SAMPLE_CATEGORIES.map((c) => (
  <option key={c} value={c}>
    {c}
  </option>
));

const meta = {
  title: 'Form/Select',
  component: Select,
  parameters: {
    layout: 'padded',
    docs: {
      description: {
        component:
          'A native `<select>` on purpose: type-ahead, Home/End, and the platform wheel picker on mobile all come for free, and no hand-rolled listbox gets every one of them right.',
      },
    },
  },
  args: { placeholder: 'Choose a category', children: options },
  decorators: [
    (Story) => (
      <div className="w-[360px]">
        <Story />
      </div>
    ),
  ],
} satisfies Meta<typeof Select>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};
export const Small: Story = { args: { size: 'sm' } };
export const Disabled: Story = { args: { disabled: true } };
export const Invalid: Story = { args: { invalid: true } };

export const InAField: Story = {
  render: (args) => (
    <Field label="Category" hint="Decides which discovery rails the campaign appears in." required>
      <Select {...args} />
    </Field>
  ),
};
