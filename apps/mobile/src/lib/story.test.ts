import { storyParagraphs } from './story';

/**
 * The story arrives from the network as a TipTap document, so the one property that
 * matters is that nothing here throws on a shape it did not expect — a campaign page
 * that crashes on an unusual story is worse than one that shows fewer paragraphs.
 */
describe('storyParagraphs', () => {
  it('reads a paragraph', () => {
    expect(
      storyParagraphs({
        type: 'doc',
        content: [{ type: 'paragraph', content: [{ type: 'text', text: 'A solar lamp.' }] }],
      }),
    ).toEqual(['A solar lamp.']);
  });

  it('joins the marks inside one paragraph rather than splitting on them', () => {
    // Bold is lost, which is the documented trade. Losing the word with it would not be.
    expect(
      storyParagraphs({
        type: 'doc',
        content: [
          {
            type: 'paragraph',
            content: [
              { type: 'text', text: 'Built ' },
              { type: 'text', text: 'entirely', marks: [{ type: 'bold' }] },
              { type: 'text', text: ' by hand.' },
            ],
          },
        ],
      }),
    ).toEqual(['Built entirely by hand.']);
  });

  it('keeps headings and list items as their own paragraphs', () => {
    expect(
      storyParagraphs({
        type: 'doc',
        content: [
          { type: 'heading', content: [{ type: 'text', text: 'The plan' }] },
          {
            type: 'bulletList',
            content: [
              { type: 'listItem', content: [{ type: 'text', text: 'Tooling' }] },
              { type: 'listItem', content: [{ type: 'text', text: 'Assembly' }] },
            ],
          },
        ],
      }),
    ).toEqual(['The plan', 'Tooling', 'Assembly']);
  });

  it('drops the empty trailing paragraph a TipTap document routinely carries', () => {
    // Three of them at the end of every campaign is a scroll view that looks broken.
    expect(
      storyParagraphs({
        type: 'doc',
        content: [
          { type: 'paragraph', content: [{ type: 'text', text: 'One.' }] },
          { type: 'paragraph' },
          { type: 'paragraph', content: [{ type: 'text', text: '   ' }] },
        ],
      }),
    ).toEqual(['One.']);
  });

  it('answers nothing rather than throwing for a shape it did not expect', () => {
    expect(storyParagraphs(null)).toEqual([]);
    expect(storyParagraphs(undefined)).toEqual([]);
    expect(storyParagraphs('a string')).toEqual([]);
    expect(storyParagraphs(42)).toEqual([]);
    expect(storyParagraphs({})).toEqual([]);
  });
});
