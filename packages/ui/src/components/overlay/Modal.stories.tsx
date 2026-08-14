import type { Meta, StoryObj } from '@storybook/react-vite';
import { useState } from 'react';
import { Modal } from './Modal';
import { Pill } from '../Pill/Pill';

const meta = {
  title: 'Overlays/Modal',
  component: Modal,
  parameters: {
    layout: 'centered',
    docs: {
      description: {
        component:
          'The only overlay that is **white**. Focus moves in on open and returns to the trigger on close; `Escape` closes the topmost overlay only.',
      },
    },
  },
  args: {
    open: false,
    onOpenChange: () => {},
    title: 'Confirm your pledge',
  },
} satisfies Meta<typeof Modal>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
  render: (args) => {
    const [open, setOpen] = useState(false);
    return (
      <>
        <Pill onClick={() => setOpen(true)}>Open modal</Pill>
        <Modal
          {...args}
          open={open}
          onOpenChange={setOpen}
          description="You are only charged if the project reaches its goal."
          footer={
            <>
              <Pill variant="ghost" onClick={() => setOpen(false)}>
                Cancel
              </Pill>
              <Pill variant="accent" onClick={() => setOpen(false)}>
                Confirm pledge
              </Pill>
            </>
          }
        >
          <div className="flex flex-col gap-3">
            <div className="flex justify-between">
              <span>Reward — Early Bird</span>
              <span className="font-medium text-on-white tabular-nums">599.00</span>
            </div>
            <div className="flex justify-between">
              <span>Shipping</span>
              <span className="font-medium text-on-white tabular-nums">25.00</span>
            </div>
          </div>
        </Modal>
      </>
    );
  },
};

/** A decision the user cannot dodge: no backdrop dismissal, no corner close. */
export const MustDecide: Story = {
  render: (args) => {
    const [open, setOpen] = useState(false);
    return (
      <>
        <Pill variant="danger" onClick={() => setOpen(true)}>
          Cancel campaign
        </Pill>
        <Modal
          {...args}
          open={open}
          onOpenChange={setOpen}
          size="sm"
          title="Cancel this campaign?"
          description="Backers are refunded in full. This cannot be undone."
          closeOnBackdropClick={false}
          closeOnEscape={false}
          showClose={false}
          footer={
            <>
              <Pill variant="ghost" onClick={() => setOpen(false)}>
                Keep it running
              </Pill>
              <Pill variant="danger" onClick={() => setOpen(false)}>
                Cancel campaign
              </Pill>
            </>
          }
        />
      </>
    );
  },
};
