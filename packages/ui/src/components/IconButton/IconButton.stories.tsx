import type { Meta, StoryObj } from '@storybook/react-vite';
import { Bell, Heart, Phone, Search, Share2, SlidersHorizontal } from 'lucide-react';
import { IconButton } from './IconButton';
import { Card, CardTitle, CardSubtitle } from '../Card/Card';
import { ExpandButton } from './ExpandButton';

const meta = {
  title: 'Primitives/IconButton',
  component: IconButton,
  parameters: {
    docs: {
      description: {
        component:
          '`label` is **required** — without an accessible name a screen reader announces only "button".',
      },
    },
  },
  argTypes: {
    variant: {
      control: 'inline-radio',
      options: ['default', 'light', 'accent', 'danger', 'ghost'],
    },
    size: { control: 'inline-radio', options: ['sm', 'md', 'lg'] },
  },
  args: { icon: <Search />, label: 'Search' },
} satisfies Meta<typeof IconButton>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Playground: Story = { args: { variant: 'default', size: 'md' } };

export const Variants: Story = {
  parameters: { layout: 'padded' },
  render: () => (
    <div className="flex flex-wrap items-center gap-3">
      <IconButton icon={<Search />} label="Search" variant="default" />
      <IconButton icon={<SlidersHorizontal />} label="Filter" variant="ghost" />
      <IconButton icon={<Share2 />} label="Share" variant="light" />
      <IconButton icon={<Heart />} label="Save" variant="accent" />
      <IconButton icon={<Phone />} label="End call" variant="danger" />
    </div>
  ),
};

export const Sizes: Story = {
  parameters: { layout: 'padded' },
  render: () => (
    <div className="flex items-center gap-3">
      <IconButton icon={<Bell />} label="Notifications" size="sm" />
      <IconButton icon={<Bell />} label="Notifications" size="md" />
      <IconButton icon={<Bell />} label="Notifications" size="lg" />
    </div>
  ),
};

/** Revealed on card hover, and on keyboard focus. */
export const Expand: Story = {
  parameters: { layout: 'padded' },
  render: () => (
    <div className="flex gap-4">
      <Card interactive className="group w-72">
        <ExpandButton label="Open project" />
        <CardTitle>Hover me</CardTitle>
        <CardSubtitle>The arrow appears top right</CardSubtitle>
      </Card>
      <Card variant="active" interactive className="group w-72">
        <ExpandButton label="Open project" onLime />
        <CardTitle>On a lime card</CardTitle>
        <CardSubtitle onLime>Switches to a black tint</CardSubtitle>
      </Card>
    </div>
  ),
};
