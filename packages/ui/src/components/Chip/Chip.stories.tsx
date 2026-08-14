import type { Meta, StoryObj } from '@storybook/react-vite';
import { useState } from 'react';
import { Flame } from 'lucide-react';
import { Chip, ChipRow } from './Chip';
import { SAMPLE_CATEGORIES } from '../../sample-data';

const meta = {
  title: 'Primitives/Chip',
  component: Chip,
  parameters: {
    docs: {
      description: {
        component:
          'The selected state is **white**, not lime — "urgent" is meaningless for a filter.',
      },
    },
  },
  args: { children: 'Technology' },
} satisfies Meta<typeof Chip>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = { args: { active: false } };
export const Active: Story = { args: { active: true } };
export const WithCount: Story = { args: { count: 24 } };

/** Horizontally scrolling filter row with a fading right edge. */
export const Row: Story = {
  parameters: { layout: 'padded' },
  render: function RowStory() {
    const [active, setActive] = useState<string>('All');
    return (
      <div className="w-[600px]">
        <ChipRow>
          {SAMPLE_CATEGORIES.map((c) => (
            <Chip key={c} active={active === c} onClick={() => setActive(c)}>
              {c}
            </Chip>
          ))}
        </ChipRow>
        <p className="mt-4 text-sm text-white/40">Selected: {active}</p>
      </div>
    );
  },
};

export const WithIconAndCount: Story = {
  parameters: { layout: 'padded' },
  render: () => (
    <ChipRow fadeEdge={false}>
      <Chip active count={2534}>
        Live
      </Chip>
      <Chip count={11054}>Upcoming</Chip>
      <Chip count={4736}>Late pledge</Chip>
      <Chip icon={<Flame className="size-3.5 text-hot" />}>Trending</Chip>
    </ChipRow>
  ),
};
