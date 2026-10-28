import type { AlarmAction, Timing } from '@alarm-actions/shared';

/**
 * Geometry of the action timeline (docs/design.md): a vertical rail around the alarm, shared by
 * the detail screen, the mini timeline in the list and the morph between them.
 */
export interface Geometry {
  /** Minutes shown on each side of the alarm. */
  range: number;
  /** Pixels per minute on the detail rail. */
  ppm: number;
  /** Total height of the timeline in pixels. */
  height: number;
  /** Minutes between labelled ticks and between minor ticks. */
  major: number;
  minor: number;
}

/** Space above the first and below the last tick. */
export const PAD = 20;
/** Width of the mini timeline on an alarm card. */
export const MINI_WIDTH = 180;
/** Height of an action card and the smallest gap between two cards. */
export const CARD_HEIGHT = 32;
const CARD_GAP = 2;

export function geometry(offsets: readonly number[]): Geometry {
  const far = offsets.reduce((m, o) => Math.max(m, Math.abs(o)), 0);
  const range = Math.max(60, Math.ceil(far / 15) * 15);
  const ppm = range <= 120 ? 6 : 720 / range;
  return {
    range,
    ppm,
    height: PAD * 2 + range * 2 * ppm,
    major: range <= 60 ? 15 : range <= 180 ? 30 : 60,
    minor: range <= 60 ? 5 : range <= 180 ? 15 : 30,
  };
}

/** Vertical position of a signed offset (negative = before the alarm). */
export function yOf(g: Geometry, offset: number): number {
  return PAD + (offset + g.range) * g.ppm;
}

/** Signed minutes from the alarm; null for `after_dismiss`, which is not on the timeline. */
export function signedOffset(a: Pick<AlarmAction, 'timing' | 'offset_minutes'>): number | null {
  switch (a.timing) {
    case 'before':
      return -(a.offset_minutes ?? 0);
    case 'at':
      return 0;
    case 'after':
      return a.offset_minutes ?? 0;
    default:
      return null;
  }
}

/** Timing and offset for a signed offset. */
export function fromSigned(offset: number): { timing: Timing; offset_minutes: number | null } {
  if (offset < 0) return { timing: 'before', offset_minutes: -offset };
  if (offset > 0) return { timing: 'after', offset_minutes: offset };
  return { timing: 'at', offset_minutes: null };
}

/** Offsets of the actions around the alarm (enabled or not). */
export function aroundOffsets(actions: readonly Pick<AlarmAction, 'timing' | 'offset_minutes'>[]) {
  return actions.map(signedOffset).filter((o): o is number => o !== null);
}

/** Card tops for the given dot positions: centred on the dot, pushed down when they would overlap. */
export function cardTops(ys: readonly number[]): number[] {
  const order = ys.map((y, i) => ({ y, i })).sort((a, b) => a.y - b.y || a.i - b.i);
  const tops = new Array<number>(ys.length);
  let last = -Infinity;
  for (const { y, i } of order) {
    const top = Math.max(y - CARD_HEIGHT / 2, last + CARD_HEIGHT + CARD_GAP);
    tops[i] = top;
    last = top;
  }
  return tops;
}

/** "HH:MM" shifted by minutes, wrapping around midnight. */
export function shiftTime(time: string, minutes: number): string {
  const [h, m] = time.split(':').map(Number) as [number, number];
  const x = (((h * 60 + m + minutes) % 1440) + 1440) % 1440;
  return `${String(Math.floor(x / 60)).padStart(2, '0')}:${String(x % 60).padStart(2, '0')}`;
}

/** Left position (percent) of an offset on the mini timeline. */
export function miniLeft(g: Geometry, offset: number): string {
  return `${Math.max(2, Math.min(98, ((offset + g.range) / (g.range * 2)) * 100))}%`;
}

export const prefersReducedMotion = () =>
  typeof window !== 'undefined' && window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;
