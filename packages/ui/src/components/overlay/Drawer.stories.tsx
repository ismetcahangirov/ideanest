import type { Meta, StoryObj } from '@storybook/react-vite';
import { useState } from 'react';
import { Drawer, type DrawerSide } from './Drawer';
import { Chip } from '../Chip/Chip';
import { Pill } from '../Pill/Pill';

const meta = {
  title: 'Overlays/Drawer',
  component: Drawer,
  parameters: {
    layout: 'centered',
    docs: {
      description: {
        component:
          'A modal dialog anchored to an edge. Dark, because it extends the page rather than interrupting it — only `Modal` is white. It slides with `translate`, never by animating `right` or `width`.',
      },
    },
  },
  args: {
    open: false,
    onOpenChange: () => {},
    title: 'Filters',
  },
} satisfies Meta<typeof Drawer>;

export default meta;
type Story = StoryObj<typeof meta>;

function DrawerDemo({ side, title }: { side: DrawerSide; title: string }) {
  const [open, setOpen] = useState(false);
  return (
    <>
      <Pill onClick={() => setOpen(true)}>Open {side} drawer</Pill>
      <Drawer
        open={open}
        onOpenChange={setOpen}
        side={side}
        title={title}
        description="Narrow the feed to what you are actually looking for."
        footer={
          <>
            <Pill variant="ghost" onClick={() => setOpen(false)}>
              Reset
            </Pill>
            <Pill variant="accent" onClick={() => setOpen(false)}>
              Show 42 projects
            </Pill>
          </>
        }
      >
        <div className="flex flex-wrap gap-2">
          <Chip active>Games</Chip>
          <Chip>Design</Chip>
          <Chip>Film</Chip>
          <Chip>Hardware</Chip>
          <Chip>Publishing</Chip>
        </div>
      </Drawer>
    </>
  );
}

export const Right: Story = {
  render: () => <DrawerDemo side="right" title="Filters" />,
};

export const Left: Story = {
  render: () => <DrawerDemo side="left" title="Categories" />,
};

/** The mobile default — the edge closest to the thumb. */
export const Bottom: Story = {
  render: () => <DrawerDemo side="bottom" title="Sort projects" />,
};
