import type { Meta, StoryObj } from '@storybook/react-vite';
import { InlineAlert } from './InlineAlert';

const meta = {
  title: 'Data/InlineAlert',
  component: InlineAlert,
  parameters: {
    layout: 'padded',
    docs: {
      description: {
        component:
          'Success is --success, never lime: lime means urgent, and a backer ' +
          'who reads it as "done" has been told the opposite of the truth. ' +
          'Every variant pairs its colour with an icon, because colour alone ' +
          'must never carry meaning.',
      },
    },
  },
  args: { children: 'Your pledge was authorised and will be captured when the campaign closes.' },
} satisfies Meta<typeof InlineAlert>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Variants: Story = {
  render: () => (
    <div className="flex max-w-lg flex-col gap-3">
      <InlineAlert variant="info" title="Pledge authorised">
        The card is charged when the campaign closes, not now.
      </InlineAlert>
      <InlineAlert variant="success" title="Goal reached">
        This campaign passed its target. Stretch goals unlock from here.
      </InlineAlert>
      <InlineAlert variant="warning" title="Closing in 6 hours">
        Pledges after the deadline are not collected.
      </InlineAlert>
      <InlineAlert variant="danger" title="Payment failed">
        The card issuer declined the charge. Update the card to keep the pledge.
      </InlineAlert>
    </div>
  ),
};

/** Only warning and danger take role="alert" — interrupting to say "saved" is a cost with no payoff. */
export const Dismissible: Story = {
  args: {
    variant: 'info',
    title: 'Draft saved',
    onDismiss: () => {},
    dismissLabel: 'Dismiss draft saved message',
  },
};
