import type { Meta, StoryObj } from '@storybook/react-vite';
import { Field } from './Field';
import { Textarea } from './Textarea';

const meta = {
  title: 'Form/Textarea',
  component: Textarea,
  parameters: {
    layout: 'padded',
    docs: {
      description: {
        component:
          '`autoGrow` writes the height directly. Height is never transitioned — it forces layout on every frame, and an easing field under a cursor is noise.',
      },
    },
  },
  args: { placeholder: 'What are you building, and why now?' },
  decorators: [
    (Story) => (
      <div className="w-[420px]">
        <Story />
      </div>
    ),
  ],
} satisfies Meta<typeof Textarea>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};
export const AutoGrow: Story = { args: { autoGrow: true, rows: 2 } };
export const Invalid: Story = { args: { invalid: true } };

export const InAField: Story = {
  render: () => (
    <Field
      label="Short description"
      hint="The first thing a backer reads."
      error="Say what the money is for."
      required
    >
      <Textarea autoGrow rows={3} />
    </Field>
  ),
};
