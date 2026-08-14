import type { Preview } from '@storybook/react-vite';
import '../src/styles.css';

const preview: Preview = {
  parameters: {
    layout: 'centered',

    // A white canvas would misrepresent every component in a dark system.
    backgrounds: {
      options: {
        'surface-1': { name: 'surface-1 (page)', value: '#0D0D0D' },
        'surface-2': { name: 'surface-2 (card)', value: '#161616' },
        black: { name: 'black', value: '#000000' },
        white: { name: 'white (floating panel context)', value: '#FFFFFF' },
      },
    },

    docs: {
      codePanel: true,
    },

    // Contrast failures are errors, not warnings. In this palette the
    // lime-on-white pairing measures 1.3:1 and is easy to miss by eye.
    a11y: {
      test: 'error',
    },
  },

  initialGlobals: {
    backgrounds: { value: 'surface-1' },
  },

  decorators: [
    (Story) => (
      <div className="font-sans text-white antialiased">
        <Story />
      </div>
    ),
  ],
};

export default preview;
