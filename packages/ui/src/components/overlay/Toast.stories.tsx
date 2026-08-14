import type { Meta, StoryObj } from '@storybook/react-vite';
import { ToastProvider, useToast } from './Toast';
import { Pill } from '../Pill/Pill';

const meta = {
  title: 'Overlays/Toast',
  component: ToastProvider,
  parameters: {
    layout: 'centered',
    docs: {
      description: {
        component:
          'Announced through a live region — polite for ordinary messages, assertive for errors — and it **never takes focus**. A toast carrying an action does not auto-dismiss: "Undo" that vanishes after five seconds is a promise the interface does not keep.',
      },
    },
  },
} satisfies Meta<typeof ToastProvider>;

export default meta;
type Story = StoryObj<typeof meta>;

function Demo() {
  const { toast } = useToast();

  return (
    <div className="flex flex-wrap gap-2">
      <Pill variant="ghost" onClick={() => toast({ title: 'Draft saved' })}>
        Info
      </Pill>
      <Pill
        variant="ghost"
        onClick={() =>
          toast({
            variant: 'success',
            title: 'Pledge confirmed',
            description: 'You are charged when the campaign reaches its goal.',
          })
        }
      >
        Success
      </Pill>
      <Pill
        variant="ghost"
        onClick={() => toast({ variant: 'warning', title: 'Campaign closes in 6 hours' })}
      >
        Warning
      </Pill>
      <Pill
        variant="ghost"
        onClick={() =>
          toast({
            variant: 'error',
            title: 'Payment declined',
            description: 'Your bank refused the charge. Nothing was taken.',
          })
        }
      >
        Error
      </Pill>
      <Pill
        variant="outline"
        onClick={() =>
          toast({
            title: 'Reward removed',
            action: { label: 'Undo', onClick: () => undefined },
          })
        }
      >
        With action (no timer)
      </Pill>
    </div>
  );
}

export const Default: Story = {
  render: () => (
    <ToastProvider>
      <Demo />
    </ToastProvider>
  ),
};
