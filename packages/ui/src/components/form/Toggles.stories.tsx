import type { Meta, StoryObj } from '@storybook/react-vite';
import { useState } from 'react';
import { Checkbox } from './Checkbox';
import { Field } from './Field';
import { Radio, RadioGroup } from './Radio';
import { Switch } from './Switch';

/**
 * Checkbox, Radio, and Switch share a page because they share one decision:
 * the "on" state is `--lime-500` with a `--text-on-lime` mark, which §8.1
 * sanctions as "active choice" — not as success.
 */
const meta = {
  title: 'Form/Toggles',
  component: Checkbox,
  parameters: {
    layout: 'padded',
    docs: {
      description: {
        component:
          'A switch is not a checkbox. A checkbox selects something for later; a switch takes effect now, and `role="switch"` is what makes a screen reader say so.',
      },
    },
  },
} satisfies Meta<typeof Checkbox>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Checkboxes: Story = {
  render: () => (
    <div className="flex w-[420px] flex-col gap-4">
      <Checkbox label="Email me when the campaign funds" />
      <Checkbox label="Show my name on the backer wall" defaultChecked />
      <Checkbox
        label="Ship to a different address"
        description="You can change this until the campaign closes."
      />
      <Checkbox label="Some rewards selected" indeterminate />
      <Checkbox label="Locked by the creator" disabled defaultChecked />
    </div>
  ),
};

/** `indeterminate` is a DOM property, so a parent row has to write it through a ref. */
export const IndeterminateParent: Story = {
  render: function IndeterminateParentStory() {
    const [picked, setPicked] = useState<string[]>(['digital']);
    const all = ['digital', 'print', 'poster'];
    const toggle = (id: string) =>
      setPicked((p) => (p.includes(id) ? p.filter((x) => x !== id) : [...p, id]));

    return (
      <div className="flex w-[420px] flex-col gap-3">
        <Checkbox
          label="All rewards"
          checked={picked.length === all.length}
          indeterminate={picked.length > 0 && picked.length < all.length}
          onChange={(e) => setPicked(e.currentTarget.checked ? all : [])}
        />
        <div className="ml-8 flex flex-col gap-3">
          {all.map((id) => (
            <Checkbox
              key={id}
              label={id}
              checked={picked.includes(id)}
              onChange={() => toggle(id)}
            />
          ))}
        </div>
      </div>
    );
  },
};

export const Radios: Story = {
  render: () => (
    <div className="w-[420px]">
      <Field label="Delivery" hint="Digital rewards arrive immediately." grouped>
        <RadioGroup defaultValue="standard">
          <Radio value="digital" label="Digital only" description="No shipping." />
          <Radio value="standard" label="Standard shipping" description="4–6 weeks." />
          <Radio value="express" label="Express shipping" description="1–2 weeks, €12." />
        </RadioGroup>
      </Field>
    </div>
  ),
};

export const Switches: Story = {
  render: function SwitchesStory() {
    const [on, setOn] = useState(true);
    return (
      <div className="flex w-[420px] flex-col gap-4">
        <Switch label="Publish updates to backers" checked={on} onCheckedChange={setOn} />
        <Switch label="Allow late pledges" />
        <Switch label="Locked while the campaign is live" disabled />
      </div>
    );
  },
};
