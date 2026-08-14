import type { Meta, StoryObj } from '@storybook/react-vite';
import { Skeleton, SkeletonCard, SkeletonGroup, SkeletonText } from './Skeleton';

const meta = {
  title: 'Data/Skeleton',
  component: Skeleton,
  parameters: {
    layout: 'padded',
    docs: {
      description: {
        component:
          'The shimmer translates a gradient overlay — transform only, never ' +
          'background-position — and disappears entirely under reduced motion. ' +
          'Placeholders are aria-hidden inside an aria-busy container, so a ' +
          'screen reader hears the message the caller wrote, never the shimmer.',
      },
    },
  },
} satisfies Meta<typeof Skeleton>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Text: Story = {
  render: () => (
    <SkeletonGroup label="Loading campaign summary" className="max-w-md">
      <SkeletonText lines={4} />
    </SkeletonGroup>
  ),
};

/** Discovery's whole motion budget: skeleton to content, nothing else (§5). */
export const CardGrid: Story = {
  render: () => (
    <SkeletonGroup label="Loading projects">
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
        <SkeletonCard />
        <SkeletonCard />
        <SkeletonCard />
      </div>
    </SkeletonGroup>
  ),
};

export const Shapes: Story = {
  render: () => (
    <SkeletonGroup label="Loading profile" className="flex max-w-md items-center gap-4">
      <Skeleton circle height="2.5rem" />
      <div className="flex-1">
        <Skeleton height="1rem" width="55%" />
        <Skeleton height="0.75rem" width="35%" className="mt-2" />
      </div>
    </SkeletonGroup>
  ),
};
