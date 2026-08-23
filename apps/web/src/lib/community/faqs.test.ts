import { describe, expect, it, vi } from 'vitest';
import { CAMPAIGN_FAQ_LIMIT, fetchProjectFaqs, readFaqList } from './faqs';

/**
 * §4.4's FAQ tab, over the public read — #283.
 *
 * WHAT THESE COVER:
 *
 *   - **the order is the creator's and nothing re-sorts it.** `sort_order` is what the
 *     editor's move controls write and what a backer reads down; a client that sorted
 *     alphabetically would silently discard the creator's decision about which question is
 *     read first, which on a campaign is usually the one about shipping.
 *   - **an unusable row is dropped and its neighbours survive.** A question with no answer is
 *     not a shorter entry — it is a row this tab cannot describe, and printing the question
 *     with a blank under it reads as a creator refusing to answer.
 *   - **a refused read is `null`, not an empty list.** "This campaign has not answered
 *     anything yet" printed over a restarting service is a claim about the creator that
 *     happens to be false — and telling those two apart is the whole reason the tab waited
 *     for an endpoint instead of shipping with a permanent empty state.
 *   - **the read is unpaged and asks for nothing extra.** §4.4 caps the list server-side and
 *     publishes no cursor, so a `limit` or a `cursor` here would be a parameter the service
 *     does not read and a page this module cannot honour.
 *   - **an answer's line breaks survive the reader.** Paragraph breaks are the only structure
 *     a plain-text answer has.
 */

describe('reading a campaign FAQ list', () => {
  it('keeps the order the service sent rather than sorting it', () => {
    const faqs = readFaqList({
      faqs: [
        { id: 'c', question: 'When does it ship?', answer: 'March.' },
        { id: 'a', question: 'Do you ship to Germany?', answer: 'Yes.' },
        { id: 'b', question: 'Is there a digital edition?', answer: 'Not yet.' },
      ],
    });

    expect(faqs.map((faq) => faq.id)).toEqual(['c', 'a', 'b']);
  });

  it('drops a row with no answer, no question or no identifier and keeps the ones beside it', () => {
    const faqs = readFaqList({
      faqs: [
        { id: 'a', question: 'Good', answer: 'Yes.' },
        { id: 'b', question: 'No answer' },
        { id: 'c', answer: 'No question.' },
        { question: 'No identifier', answer: 'Yes.' },
        { id: 'd', question: 'Also good', answer: 'Also yes.' },
      ],
    });

    expect(faqs.map((faq) => faq.id)).toEqual(['a', 'd']);
  });

  /**
   * A blank answer is refused by the service, so a blank one arriving means something else
   * went wrong. Either way it is not a row a reader can be shown.
   */
  it('treats a blank answer as no answer', () => {
    expect(readFaqList({ faqs: [{ id: 'a', question: 'Q', answer: '   ' }] })).toEqual([]);
  });

  it('keeps the line breaks inside an answer, because they are its only structure', () => {
    const faqs = readFaqList({
      faqs: [{ id: 'a', question: 'How?', answer: 'First this.\n\nThen that.' }],
    });

    expect(faqs[0]?.answer).toBe('First this.\n\nThen that.');
  });

  it('answers an empty list for a body that is not one', () => {
    expect(readFaqList(null)).toEqual([]);
    expect(readFaqList('nope')).toEqual([]);
    expect(readFaqList({ faqs: 'nope' })).toEqual([]);
  });

  /** Nothing may push into the list a campaign with no questions shares. */
  it('answers a frozen list rather than one a caller can append to', () => {
    expect(Object.isFrozen(readFaqList({}))).toBe(true);
  });
});

describe('fetching a campaign FAQ list', () => {
  function respondWith(body: unknown, status = 200): typeof fetch {
    return vi.fn().mockResolvedValue(
      new Response(JSON.stringify(body), {
        status,
        headers: { 'content-type': 'application/json' },
      }),
    ) as unknown as typeof fetch;
  }

  it('asks the public endpoint and reads what comes back', async () => {
    const fetchImpl = respondWith({
      faqs: [{ id: 'a', question: 'Do you ship to Germany?', answer: 'Yes.' }],
    });

    const faqs = await fetchProjectFaqs('p1', {
      fetchImpl,
      env: { IDEANEST_API_ORIGIN: 'https://api.test' },
    });

    expect(faqs).toHaveLength(1);

    const [url] = vi.mocked(fetchImpl).mock.calls[0] as [string];
    expect(url).toBe('https://api.test/v1/projects/p1/faqs');
  });

  /**
   * §4.4 caps the list at fifty entries server-side and publishes no cursor. The cap is what
   * makes the absent cursor honest, so this read sends neither — a `limit` the service does
   * not bind is a parameter that looks like a contract and is not one.
   */
  it('sends no paging parameters, because the read is not paged', async () => {
    const fetchImpl = respondWith({ faqs: [] });

    await fetchProjectFaqs('p1', {
      fetchImpl,
      env: { IDEANEST_API_ORIGIN: 'https://api.test' },
    });

    const [url] = vi.mocked(fetchImpl).mock.calls[0] as [string];
    expect(url).not.toContain('?');
    expect(CAMPAIGN_FAQ_LIMIT).toBe(50);
  });

  it('answers null when the service refuses, so the tab can tell that from an empty campaign', async () => {
    const faqs = await fetchProjectFaqs('p1', {
      fetchImpl: respondWith({ title: 'Not found' }, 404),
      env: { IDEANEST_API_ORIGIN: 'https://api.test' },
    });

    expect(faqs).toBeNull();
  });

  it('answers null when the service cannot be reached at all', async () => {
    const faqs = await fetchProjectFaqs('p1', {
      fetchImpl: vi.fn().mockRejectedValue(new TypeError('fetch failed')) as unknown as typeof fetch,
      env: { IDEANEST_API_ORIGIN: 'https://api.test' },
    });

    expect(faqs).toBeNull();
  });

  it('answers an empty list, not null, for a campaign that has answered nothing', async () => {
    const faqs = await fetchProjectFaqs('p1', {
      fetchImpl: respondWith({ faqs: [] }),
      env: { IDEANEST_API_ORIGIN: 'https://api.test' },
    });

    expect(faqs).toEqual([]);
  });
});
