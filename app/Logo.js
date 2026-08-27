/**
 * The kPlay mark.
 *
 * A lowercase "k" whose arm and leg are a single play triangle: the stem is
 * the letter, the triangle is both the rest of the letter and the play glyph.
 * One shape says the name and says what the app does, which is what the old
 * plain "K" tile could not.
 *
 * Drawn on a 48-unit grid so the same geometry can be restated as an Android
 * vector drawable (android-tv/app/src/main/res/drawable/ic_launcher_foreground.xml)
 * without redrawing it by eye.
 *
 * `size` is the box edge in pixels; the badge and the mark scale together.
 */
export default function Logo({ size = 32, className = '', rounded = 'rounded-lg' }) {
  return (
    <span
      className={`flex shrink-0 items-center justify-center bg-[var(--accent)] ${rounded} ${className}`}
      style={{ width: size, height: size }}
      aria-hidden="true"
    >
      <LogoMark size={size * 0.66} />
    </span>
  );
}

/** The bare glyph, no badge — for places that supply their own backdrop. */
export function LogoMark({ size = 24, color = '#fff' }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 48 48"
      fill="none"
      xmlns="http://www.w3.org/2000/svg"
      aria-hidden="true"
    >
      {/* stem of the k */}
      <rect x="10" y="9" width="6" height="30" rx="3" fill={color} />
      {/* arm, leg and play triangle, all one shape */}
      <path d="M21 12.6 L39.2 24 L21 35.4 Z" fill={color} strokeLinejoin="round" />
    </svg>
  );
}
