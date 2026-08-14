import type { Meta, StoryObj } from '@storybook/react-vite';
import { Avatar, AvatarGroup } from './Avatar';

const meta = {
  title: 'Primitives/Avatar',
  component: Avatar,
  args: { name: 'Amara Osei' },
  argTypes: { size: { control: 'inline-radio', options: ['xs', 'sm', 'md', 'lg'] } },
} satisfies Meta<typeof Avatar>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Fallback: Story = {
  args: { size: 'md' },
  parameters: {
    docs: { description: { story: 'With no image, initials are derived from the name.' } },
  },
};

export const Sizes: Story = {
  parameters: { layout: 'padded' },
  render: () => (
    <div className="flex items-end gap-4">
      <Avatar name="Rowan Hale" size="xs" />
      <Avatar name="Rowan Hale" size="sm" />
      <Avatar name="Rowan Hale" size="md" />
      <Avatar name="Rowan Hale" size="lg" />
    </div>
  ),
};

/** Hover the stack — the faces spread apart. */
export const Group: Story = {
  parameters: { layout: 'padded' },
  render: () => (
    <div className="flex flex-col gap-6">
      <AvatarGroup>
        <Avatar name="Amara Osei" />
        <Avatar name="Rowan Hale" />
        <Avatar name="Nina Halvorsen" />
        <Avatar name="Elias Nordin" />
      </AvatarGroup>

      <AvatarGroup max={3} total={1697}>
        <Avatar name="Amara Osei" />
        <Avatar name="Rowan Hale" />
        <Avatar name="Nina Halvorsen" />
        <Avatar name="Elias Nordin" />
      </AvatarGroup>
    </div>
  ),
};
