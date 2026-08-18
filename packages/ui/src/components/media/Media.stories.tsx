import type { Meta, StoryObj } from '@storybook/react-vite';
import { Media, MediaFrame } from './Media';
import { SAMPLE_COVER, SAMPLE_COVER_PLACEHOLDER } from '../../sample-data';

const meta = {
  title: 'Media/Media',
  component: Media,
  parameters: {
    layout: 'padded',
    docs: {
      description: {
        component:
          'The reserved box is the point. Every story here has its height ' +
          'decided before the image arrives, so nothing under it moves when ' +
          'the bytes land. `ratio` takes a crop token when the surface cuts ' +
          'the picture and the image’s own `{ width, height }` when it ' +
          'shows the picture whole. Nothing animates: a card grid that ' +
          'cross-fades twenty-four covers is the long-list animation ' +
          'docs/motion-system.md §8 forbids.',
      },
    },
  },
  args: {
    src: SAMPLE_COVER,
    ratio: '16/9',
    decorative: true,
  },
} satisfies Meta<typeof Media>;

export default meta;
type Story = StoryObj<typeof meta>;

/** The discovery card's crop: every cover becomes 16:9 whatever was uploaded. */
export const Cropped: Story = {
  args: { ratio: '16/9', radius: 'lg', decorative: true },
};

/**
 * A content image. `alt` is a sentence about what the picture shows, because a
 * screen reader otherwise reads the file name.
 */
export const Described: Story = {
  args: {
    ratio: '16/9',
    radius: 'lg',
    decorative: false,
    alt: 'A hand-built field recorder on a workbench, its lid open.',
  },
};

/**
 * The placeholder, painted blurred inside the reserved box. It is a `data:` URI
 * of about half a kilobyte, so it arrives with the markup rather than as a
 * second request.
 */
export const WithPlaceholder: Story = {
  args: {
    ratio: '16/9',
    radius: 'lg',
    placeholder: SAMPLE_COVER_PLACEHOLDER,
    decorative: true,
  },
};

/**
 * An intrinsic ratio. A portrait photograph shown whole reserves the shape it
 * really is; forcing it into 16:9 would be a layout shift with extra steps.
 */
export const IntrinsicRatio: Story = {
  args: {
    ratio: { width: 3, height: 4 },
    radius: 'lg',
    fit: 'contain',
    placeholder: SAMPLE_COVER_PLACEHOLDER,
    decorative: true,
  },
  render: (args) => (
    <div className="max-w-[240px]">
      <Media {...args} />
    </div>
  ),
};

/**
 * The frame with nothing in it. A campaign with no cover gets the reserved
 * surface rather than a broken image or a stock graphic that says nothing — and
 * the card below it sits exactly where it will sit once a cover exists.
 */
export const Empty: Story = {
  render: () => <MediaFrame ratio="16/9" radius="lg" />,
};

/**
 * Every crop token, beside each other. There is no fifth without a design
 * decision, which is the rule radii and durations already follow.
 */
export const Ratios: Story = {
  render: () => (
    <div className="grid max-w-[720px] grid-cols-4 gap-4">
      <Media src={SAMPLE_COVER} ratio="16/9" radius="md" decorative />
      <Media src={SAMPLE_COVER} ratio="3/2" radius="md" decorative />
      <Media src={SAMPLE_COVER} ratio="4/3" radius="md" decorative />
      <Media src={SAMPLE_COVER} ratio="1/1" radius="md" decorative />
    </div>
  ),
};
