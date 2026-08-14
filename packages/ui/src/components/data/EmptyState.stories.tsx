import type { Meta, StoryObj } from '@storybook/react-vite';
import { EmptyState } from './EmptyState';
import { Pill } from '../Pill/Pill';

const meta = {
  title: 'Data/EmptyState',
  component: EmptyState,
  parameters: {
    layout: 'padded',
    docs: {
      description: {
        component:
          'Two variants because the recovery differs: "nothing exists yet" ' +
          'sends the user to create, "your filter matched nothing" sends them ' +
          'to clear the filter. The icon is decorative; the title carries it.',
      },
    },
  },
  args: { title: 'No campaigns yet' },
} satisfies Meta<typeof EmptyState>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Empty: Story = {
  args: {
    variant: 'empty',
    title: 'No campaigns yet',
    description: 'Your first campaign will appear here once you publish it.',
    action: <Pill variant="primary">New campaign</Pill>,
  },
};

export const Filtered: Story = {
  args: {
    variant: 'filtered',
    title: 'No campaigns match these filters',
    description: 'Six campaigns exist, but none of them are in Games and closing this week.',
    action: <Pill variant="outline">Clear filters</Pill>,
  },
};
