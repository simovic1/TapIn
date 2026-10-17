import { useEffect, useRef } from 'react';
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router';
import { navigationFor, splitForMobile, type NavItem } from '../../app/navigation';
import { ROLE_LABELS } from '../../lib/session/roles';
import { useCurrentUser, useLogout } from '../../lib/session/session';
import { Icon } from '../ui/Icon';
import { BeltMark } from './BeltMark';
import { MoreMenu } from './MoreMenu';

/** On the ink header the active page wears a crimson tape, like the rank bar of the mark. */
const desktopLink = ({ isActive }: { isActive: boolean }) =>
  `inline-flex h-full min-h-14 items-center border-b-[3px] px-3 pt-[3px] text-sm font-semibold whitespace-nowrap transition-colors ${
    isActive ? 'border-dragon-500 text-white' : 'border-transparent text-slate-300 hover:text-white'
  }`;

const mobileLink = ({ isActive }: { isActive: boolean }) =>
  `relative flex min-h-14 flex-1 flex-col items-center justify-center gap-0.5 text-xs font-medium ${
    isActive
      ? 'text-slate-900 before:absolute before:inset-x-3 before:top-0 before:h-[3px] before:bg-dragon-600 dark:text-white dark:before:bg-dragon-400'
      : 'text-slate-500 dark:text-slate-400'
  }`;

export function AppLayout() {
  const { data: user } = useCurrentUser();
  const logout = useLogout();
  const navigate = useNavigate();
  const { pathname } = useLocation();
  const mainRef = useRef<HTMLElement>(null);
  const firstRender = useRef(true);

  // Move focus to the content after client-side navigation so keyboard and screen-reader users start there.
  useEffect(() => {
    if (firstRender.current) {
      firstRender.current = false;
      return;
    }
    mainRef.current?.focus();
  }, [pathname]);

  if (!user) return null; // RequireAuth guarantees a user; this only narrows the type.

  const items = navigationFor(user.roles);
  const { bar, more } = splitForMobile(items);

  const onLogout = () =>
    logout.mutate(undefined, {
      onSettled: () => navigate('/prijava', { replace: true }),
    });

  return (
    <div className="min-h-dvh pb-[calc(3.5rem+env(safe-area-inset-bottom))] md:pb-0">
      <a
        href="#main"
        className="sr-only z-50 rounded-lg bg-white px-4 py-2 font-semibold text-slate-900 shadow focus:not-sr-only focus:fixed focus:top-2 focus:left-2"
      >
        Preskoči na sadržaj
      </a>

      <header className="sticky top-0 z-20 bg-ink text-white">
        <div className="mx-auto flex h-14 max-w-5xl items-stretch gap-4 pr-4 md:px-4">
          <NavLink to="/" aria-label={`TapIn – ${user.club.name}, početna`} className="flex">
            <BeltMark club={user.club.name} />
          </NavLink>
          <nav aria-label="Glavna navigacija" className="hidden flex-1 md:block">
            <ul className="flex h-full gap-1">
              {items.map((item) => (
                <li key={item.to} className="flex">
                  <NavLink to={item.to} end={item.to === '/'} className={desktopLink}>
                    {item.label}
                  </NavLink>
                </li>
              ))}
            </ul>
          </nav>
          <div className="ml-auto flex items-center gap-3 md:ml-0">
            <div className="text-right text-sm leading-tight">
              <p className="font-medium">
                {user.firstName} {user.lastName}
              </p>
              <p className="text-xs text-slate-400">
                {user.roles.map((role) => ROLE_LABELS[role]).join(' · ')}
              </p>
            </div>
            <button
              type="button"
              onClick={onLogout}
              disabled={logout.isPending}
              className="hidden min-h-11 items-center gap-2 rounded-lg px-3 text-sm font-medium text-slate-300 hover:bg-white/10 hover:text-white md:inline-flex"
            >
              <Icon name="logout" className="size-4" />
              Odjava
            </button>
          </div>
        </div>
      </header>

      <main id="main" ref={mainRef} tabIndex={-1} className="mx-auto max-w-5xl px-4 py-6 outline-none">
        <Outlet />
      </main>

      <nav
        aria-label="Glavna navigacija"
        className="fixed inset-x-0 bottom-0 z-20 border-t border-slate-200 bg-white pb-[env(safe-area-inset-bottom)] md:hidden dark:border-slate-800 dark:bg-slate-900"
      >
        <ul className="flex">
          {bar.map((item: NavItem) => (
            <li key={item.to} className="flex flex-1">
              <NavLink to={item.to} end={item.to === '/'} className={mobileLink}>
                <Icon name={item.icon} />
                {item.label}
              </NavLink>
            </li>
          ))}
          <li className="flex flex-1">
            <MoreMenu items={more} onLogout={onLogout} loggingOut={logout.isPending} />
          </li>
        </ul>
      </nav>
    </div>
  );
}
