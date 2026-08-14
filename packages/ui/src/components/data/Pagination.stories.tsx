import type { Meta, StoryObj } from '@storybook/react-vite';
import { useState } from 'react';
import { Pagination } from './Pagination';

const meta = {
  title: 'Data/Pagination',
  component: Pagination,
  parameters: {
    layout: 'padded',
    docs: {
      description: {
        component:
          'The active page is white, not lime. Lime means urgent; page 3 of 12 ' +
          'is merely where you are. Boundary controls are disabled, not hidden.',
      },
    },
  },
  args: { page: 1, pageCount: 12, onPageChange: () => {} },
} satisfies Meta<typeof Pagination>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Interactive: Story = {
  render: (args) => {
    const [page, setPage] = useState(args.page);
    return <Pagination {...args} page={page} onPageChange={setPage} />;
  },
};

/** Previous is disabled rather than removed, so the boundary is stated. */
export const FirstPage: Story = { args: { page: 1 } };

export const LastPage: Story = { args: { page: 12 } };

/** Long ranges collapse behind an ellipsis, which is decoration and aria-hidden. */
export const Collapsed: Story = { args: { page: 42, pageCount: 99 } };

/** One page: the controls are inert on both sides. */
export const SinglePage: Story = { args: { page: 1, pageCount: 1 } };
