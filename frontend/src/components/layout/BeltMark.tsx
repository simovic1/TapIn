/**
 * The club mark: the red rank bar of a black belt, carrying the club name and "TapIn". It sits on an ink-black
 * surface (header, login card) so the pair reads as the belt itself. No stripes - the app has none (spec §3.3).
 */
export function BeltMark({ club, size = 'header' }: { club?: string | null; size?: 'header' | 'hero' }) {
  const hero = size === 'hero';
  return (
    <span
      className={`flex flex-col justify-center bg-dragon-600 leading-none text-white ${
        hero ? 'gap-1.5 px-6 py-4' : 'h-full gap-1 px-4'
      }`}
    >
      {club && (
        <span
          className={`max-w-[11rem] truncate font-semibold tracking-[0.18em] text-dragon-100 uppercase ${
            hero ? 'max-w-none text-xs' : 'text-[0.625rem]'
          }`}
        >
          {club}
        </span>
      )}
      <span className={`font-display font-extrabold tracking-wide uppercase ${hero ? 'text-5xl' : 'text-2xl'}`}>TapIn</span>
    </span>
  );
}
