import type { Meta, StoryObj } from '@storybook/react-vite';
import { StatBlock, StatRow } from './StatBlock';

const meta = {
  title: 'Primitives/StatBlock',
  component: StatBlock,
  parameters: { layout: 'padded' },
  args: { value: '1,697', label: 'backers', badge: '+12' },
} satisfies Meta<typeof StatBlock>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};

/** Headline row across the top of a creator dashboard. */
export const DashboardRow: Story = {
  render: () => (
    <StatRow>
      <StatBlock value="1,697" label="backers" badge="+12" badgeTone="up" />
      <StatBlock value="1,111%" label="funded" badge="+3" badgeTone="up" />
      <StatBlock value="26" label="days left" />
      <StatBlock value="14" label="failed payments" badge="+2" badgeTone="down" />
    </StatRow>
  ),
};

export const Sizes: Story = {
  render: () => (
    <div className="flex flex-col gap-8">
      <StatBlock size="lg" value="1,111,561" label="pledged" />
      <StatBlock size="md" value="100,000" label="goal" />
    </div>
  ),
};
