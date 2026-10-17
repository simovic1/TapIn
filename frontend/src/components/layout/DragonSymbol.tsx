/**
 * The Black Dragon sign (club logo symbol without the lettering), redrawn as vector shapes. White strokes with the
 * red flag; meant for ink-black surfaces. The same shapes produce the favicon and app icons in /public.
 */
export function DragonSymbol({ className = '' }: { className?: string }) {
  return (
    <svg viewBox="199 56 264 349" className={className} aria-hidden="true" focusable="false">
      <g fill="#fff">
        <polygon points="280,92 316,56 316,339 280,375" />
        <polygon points="324,226 434,226 402,258 324,258" />
        <polygon points="441,223 463,259 316,405 301,402 288,390 288,381" />
      </g>
      <polygon className="fill-dragon-600" points="199,226 271,226 271,258 231,258" />
    </svg>
  );
}
