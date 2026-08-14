import type { Meta, StoryObj } from '@storybook/react-vite';
import { Field } from './Field';
import { TextInput } from './TextInput';

const meta = {
  title: 'Form/Field',
  component: Field,
  parameters: {
    layout: 'padded',
    docs: {
      description: {
        component:
          'Owns the ids. The error is **text plus an icon** — a red border alone says nothing to a screen reader and nothing to a colour-blind user.',
      },
    },
  },
  args: {
    label: 'Campaign title',
    children: <TextInput placeholder="A sentence people can repeat" />,
  },
  render: (args) => (
    <div className="w-[360px]">
      <Field {...args} />
    </div>
  ),
} satisfies Meta<typeof Field>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};

export const WithHint: Story = {
  args: { hint: 'Shown on the discovery grid. Sixty characters or fewer.' },
};

export const Required: Story = { args: { required: true } };

/** Both descriptions are wired: `aria-describedby` names the hint and the error. */
export const Errored: Story = {
  args: {
    hint: 'Sixty characters or fewer.',
    error: 'Titles longer than sixty characters are truncated on mobile.',
  },
};
