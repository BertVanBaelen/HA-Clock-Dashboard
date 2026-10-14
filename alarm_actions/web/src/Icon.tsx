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
