import type { Meta, StoryObj } from '@storybook/react-vite';
import { Info, Share2 } from 'lucide-react';
import { Tooltip } from './Tooltip';
import { IconButton } from '../IconButton/IconButton';

const meta = {
  title: 'Overlays/Tooltip',
  component: Tooltip,
  parameters: {
    layout: 'centered',
    docs: {
      description: {
        component:
          'Opens on hover **and** on focus — hover alone is content that exists for mouse users and nobody else. Linked with `aria-describedby`, so it is announced after the trigger name rather than replacing it. Never put a control inside one.',
      },
    },
  },
  args: {
    label: 'Copy the campaign link',
    children: <IconButton icon={<Share2 />} label="Share" />,
  },
} satisfies Meta<typeof Tooltip>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};

export const Placements: Story = {
  render: () => (
    <div className="flex items-center gap-4">
      <Tooltip label="Above the trigger" placement="top">
        <IconButton icon={<Info />} label="Top" />
      </Tooltip>
      <Tooltip label="Below the trigger" placement="bottom">
        <IconButton icon={<Info />} label="Bottom" />
      </Tooltip>
      <Tooltip label="Beside the trigger" placement="right">
        <IconButton icon={<Info />} label="Right" />
      </Tooltip>
    </div>
  ),
};
