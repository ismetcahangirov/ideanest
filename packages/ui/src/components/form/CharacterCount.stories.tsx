import type { Meta, StoryObj } from '@storybook/react-vite';
import { CharacterCount } from './CharacterCount';
import { Field } from './Field';
import { Textarea } from './Textarea';

const SUMMARY =
  'A pocket-sized field recorder for people who write music outdoors, built from parts that can be replaced with a screwdriver.';

const meta = {
  title: 'Form/CharacterCount',
  component: CharacterCount,
  parameters: {
    layout: 'padded',
    docs: {
      description: {
        component:
          'Passing the limit changes the **wording** first and the colour second — a counter that only turns red says nothing to a colour-blind creator and nothing at all to a screen reader. The visible number is `aria-hidden`; a separate polite live region announces the remainder only once it is close, so the count never talks over the typing echo.',
      },
    },
  },
  args: { count: 24, limit: 135 },
  decorators: [
    (Story) => (
      <div className="w-[420px]">
        <Story />
      </div>
    ),
  ],
} satisfies Meta<typeof CharacterCount>;

export default meta;
type Story = StoryObj<typeof meta>;

/** Well inside the limit: present, quiet, and not announced at all. */
export const Default: Story = {};

/** Inside `announceWithin`, so the live region has something to say. */
export const NearTheLimit: Story = { args: { count: 129 } };

/** Over it. The sentence changes, and only then the colour. */
export const OverTheLimit: Story = { args: { count: 141 } };

/** Where it actually lives: under the control, inside the `Field`. */
export const InAField: Story = {
  args: { count: SUMMARY.length, limit: 135 },
  render: (args) => (
    <Field
      label="Summary"
      hint="Shown on the discovery grid and in search results. 135 characters or fewer."
      required
    >
      <Textarea defaultValue={SUMMARY} rows={3} />
      <CharacterCount {...args} />
    </Field>
  ),
};
