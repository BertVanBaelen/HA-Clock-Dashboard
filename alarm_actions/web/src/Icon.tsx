/** 24×24 SVG icon from an MDI path (bundled `@mdi/js` constants or server-resolved script icons). */
export function Icon({ path, size = 24, title }: { path: string; size?: number; title?: string }) {
  return (
    <svg
      className="icon"
      width={size}
      height={size}
      viewBox="0 0 24 24"
      aria-hidden={title ? undefined : true}
      role={title ? 'img' : undefined}
    >
      {title ? <title>{title}</title> : null}
      <path d={path} fill="currentColor" />
    </svg>
  );
}

/** Line icons of the design (docs/design.md), drawn with a 1.75 stroke on a 24×24 grid. */
export const STROKE = {
  back: 'M15 6l-6 6 6 6',
  plus: 'M12 5v14M5 12h14',
  up: 'M6 15l6-6 6 6',
  down: 'M6 9l6 6 6-6',
  move: 'M8 9l4-4 4 4M8 15l4 4 4-4',
  more: 'M5 12h.01M12 12h.01M19 12h.01',
  repeat: 'M17 2l4 4-4 4M3 11V9a3 3 0 0 1 3-3h15M7 22l-4-4 4-4M21 13v2a3 3 0 0 1-3 3H3',
  snooze: 'M12 8v5l3 2M5 3 2 6M22 6l-3-3M12 21a8 8 0 1 0 0-16 8 8 0 0 0 0 16z',
} as const;

export function StrokeIcon({ d, size = 20, width = 1.75 }: { d: string; size?: number; width?: number }) {
  return (
    <svg
      className="icon"
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={width}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      <path d={d} />
    </svg>
  );
}
