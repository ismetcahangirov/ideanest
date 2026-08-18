import { describe, expect, it } from 'vitest';
import { LocalSink, RUM_EVENT, logLine, recordsFrom } from './sink';
import { PAYLOAD_VERSION, type RumPayload } from './payload';

const payload: RumPayload = {
  v: PAYLOAD_VERSION,
  requestId: '019432f1-2c4a-7bb1-9f7e-0f21b7c9a4d2',
  traceId: '4bf92f3577b34da6a3ce929d0e0e4736',
  spanId: '00f067aa0ba902b7',
  sessionId: '0f4b7a2c-1d3e-4f5a-8b9c-0d1e2f3a4b5c',
  route: '/discover',
  connection: '4g',
  device: 'mobile',
  samples: [
    { name: 'LCP', value: 1822, navigationType: 'navigate' },
    { name: 'CLS', value: 0.3, navigationType: 'navigate' },
  ],
};

const at = new Date('2026-08-18T09:00:00.000Z');

describe('recordsFrom', () => {
  it('explodes a beacon into one record per sample', () => {
    const records = recordsFrom(payload, at);

    expect(records).toHaveLength(2);
    expect(records[0]).toEqual({
      at: '2026-08-18T09:00:00.000Z',
      requestId: payload.requestId,
      traceId: payload.traceId,
      spanId: payload.spanId,
      sessionId: payload.sessionId,
      route: '/discover',
      metric: 'LCP',
      value: 1822,
      rating: 'good',
      navigationType: 'navigate',
      connection: '4g',
      device: 'mobile',
    });
  });

  /*
   * A clock the sender controls is a clock that can place a sample outside the
   * window it belongs to, so the timestamp is the server's.
   */
  it('stamps arrival rather than trusting the sender', () => {
    expect(recordsFrom(payload, at).every((record) => record.at === at.toISOString())).toBe(true);
  });

  it('derives the rating rather than reading it off the wire', () => {
    expect(recordsFrom(payload, at)[1]).toMatchObject({ metric: 'CLS', rating: 'poor' });
  });
});

describe('logLine', () => {
  it('is one line of JSON carrying §18.1s field names', () => {
    const line = logLine(recordsFrom(payload, at)[0]!);

    expect(line).not.toContain('\n');
    const parsed = JSON.parse(line);
    expect(parsed.event).toBe(RUM_EVENT);
    for (const field of ['requestId', 'traceId', 'spanId']) {
      expect(parsed).toHaveProperty(field);
    }
  });
});

describe('LocalSink', () => {
  it('keeps what it is given and answers with observations', () => {
    const sink = new LocalSink();
    sink.accept(recordsFrom(payload, at));

    expect(sink.size()).toBe(2);
    expect(sink.observations()).toEqual([
      { route: '/discover', name: 'LCP', value: 1822 },
      { route: '/discover', name: 'CLS', value: 0.3 },
    ]);
  });

  // A developer asking "what has just been arriving" wants the recent end.
  it('drops the oldest once it is full', () => {
    const sink = new LocalSink(3);
    for (let index = 0; index < 10; index += 1) {
      sink.accept(
        recordsFrom({ ...payload, samples: [{ name: 'LCP', value: index, navigationType: 'navigate' }] }, at),
      );
    }

    expect(sink.size()).toBe(3);
    expect(sink.observations().map((observation) => observation.value)).toEqual([7, 8, 9]);
  });
});
