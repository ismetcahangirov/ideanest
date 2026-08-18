import { describe, expect, it } from 'vitest';

import { faqPageNode } from './faq';

const url = 'https://ideanest.az/projects/ayan/studio';

interface Question {
  readonly '@type': string;
  readonly name: string;
  readonly acceptedAnswer: { readonly '@type': string; readonly text: string };
}

function questions(node: ReturnType<typeof faqPageNode>): readonly Question[] {
  return (node?.['mainEntity'] ?? []) as unknown as readonly Question[];
}

describe('faqPageNode', () => {
  it('states each pair as a question with one accepted answer', () => {
    const node = faqPageNode(
      [
        { question: 'When does it ship?', answer: 'March 2027, to every backer at once.' },
        { question: 'Can I change my tier?', answer: 'Until the deadline, yes.' },
      ],
      url,
    );

    expect(node?.['@type']).toBe('FAQPage');
    expect(node?.['@id']).toBe(`${url}#faq`);
    expect(questions(node)).toEqual([
      {
        '@type': 'Question',
        name: 'When does it ship?',
        acceptedAnswer: { '@type': 'Answer', text: 'March 2027, to every backer at once.' },
      },
      {
        '@type': 'Question',
        name: 'Can I change my tier?',
        acceptedAnswer: { '@type': 'Answer', text: 'Until the deadline, yes.' },
      },
    ]);
  });

  it('is null when the campaign has published no questions', () => {
    expect(faqPageNode([], url)).toBeNull();
  });

  it('drops a half-written pair rather than claiming an unanswered question', () => {
    const node = faqPageNode(
      [
        { question: 'When does it ship?', answer: '   ' },
        { question: '', answer: 'An answer to nothing.' },
        { question: 'Can I change my tier?', answer: 'Until the deadline, yes.' },
      ],
      url,
    );

    expect(questions(node).map((question) => question.name)).toEqual(['Can I change my tier?']);
  });

  it('is null when nothing survived the drop', () => {
    expect(faqPageNode([{ question: 'When does it ship?', answer: '' }], url)).toBeNull();
  });

  it('collapses the whitespace a textarea put in an answer', () => {
    const node = faqPageNode(
      [{ question: ' When does it\tship? ', answer: 'March 2027.\n\nTo every backer.' }],
      url,
    );

    expect(questions(node)[0]?.name).toBe('When does it ship?');
    expect(questions(node)[0]?.acceptedAnswer.text).toBe('March 2027. To every backer.');
  });
});
