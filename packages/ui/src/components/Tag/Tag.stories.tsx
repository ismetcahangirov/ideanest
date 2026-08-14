import type { Meta, StoryObj } from '@storybook/react-vite';
import { Flame } from 'lucide-react';
import { Tag } from './Tag';
import { Card, CardTitle } from '../Card/Card';

const meta = {
  title: 'Primitives/Tag',
  component: Tag,
  args: { children: 'Technology' },
} satisfies Meta<typeof Tag>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};

export const Variants: Story = {
  parameters: { layout: 'padded' },
  render: () => (
    <div className="flex flex-wrap items-center gap-2">
      <Tag>default</Tag>
      <Tag variant="success">Goal reached</Tag>
      <Tag variant="warning">Final 48 hours</Tag>
      <Tag variant="danger">Payment failed</Tag>
      <Tag variant="hot">
        <Flame className="size-3" /> Trending
      </Tag>
    </div>
  ),
};

/**
 * The `default` variant is invisible inside a lime card — dark on dark.
 * `onLime` uses a black tint instead.
 */
export const ContextMatters: Story = {
  parameters: { layout: 'padded' },
  render: () => (
    <div className="flex gap-4">
      <Card variant="active" className="w-72">
        <CardTitle>Wrong</CardTitle>
        <div className="mt-3 flex gap-2">
          <Tag>Invisible</Tag>
          <Tag>Invisible</Tag>
        </div>
      </Card>
      <Card variant="active" className="w-72">
        <CardTitle>Correct</CardTitle>
        <div className="mt-3 flex gap-2">
          <Tag variant="onLime">Games</Tag>
          <Tag variant="onLime">Bristol</Tag>
        </div>
      </Card>
    </div>
  ),
};
