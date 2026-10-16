import { useEffect, useId, useRef, useState } from 'react';
import { NavLink, useLocation } from 'react-router';
import type { NavItem } from '../../app/navigation';
import { Icon } from '../ui/Icon';

interface MoreMenuProps {
  items: NavItem[];
  onLogout: () => void;
  loggingOut: boolean;
}

/**
 * Mobile overflow menu ("Više"): disclosure button + panel. Escape or an outside click closes it and returns focus
 * to the button; it closes automatically after navigation.
 */
export function MoreMenu({ items, onLogout, loggingOut }: MoreMenuProps) {
  const [open, setOpen] = useState(false);
  const panelId = useId();
  const buttonRef = useRef<HTMLButtonElement>(null);
  const panelRef = useRef<HTMLDivElement>(null);
  const { pathname } = useLocation();
  const activeInside = items.some((item) => item.to === pathname);

  useEffect(() => setOpen(false), [pathname]);

  useEffect(() => {
    if (!open) return;
    panelRef.current?.querySelector<HTMLElement>('a, button')?.focus();

    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setOpen(false);
        buttonRef.current?.focus();
      }
    };
    const onPointerDown = (event: PointerEvent) => {
      const target = event.target as Node;
      if (!panelRef.current?.contains(target) && !buttonRef.current?.contains(target)) setOpen(false);
    };
    document.addEventListener('keydown', onKeyDown);
    document.addEventListener('pointerdown', onPointerDown);
    return () => {
      document.removeEventListener('keydown', onKeyDown);
      document.removeEventListener('pointerdown', onPointerDown);
    };
  }, [open]);

  return (
    <>
      <button
        ref={buttonRef}
        type="button"
        aria-expanded={open}
        aria-controls={panelId}
        onClick={() => setOpen((value) => !value)}
        className={`flex min-h-14 flex-1 flex-col items-center justify-center gap-0.5 text-xs font-medium ${
          open || activeInside ? 'text-slate-900 dark:text-white' : 'text-slate-500 dark:text-slate-400'
        }`}
      >
        <Icon name={open ? 'close' : 'menu'} />
        Više
      </button>

      {open && (
        <div
          ref={panelRef}
          id={panelId}
          className="fixed inset-x-2 bottom-[calc(4rem+env(safe-area-inset-bottom))] z-30 rounded-2xl border border-slate-200 bg-white p-2 shadow-lg dark:border-slate-700 dark:bg-slate-900"
        >
          <ul>
            {items.map((item) => (
              <li key={item.to}>
                <NavLink
                  to={item.to}
                  className={({ isActive }) =>
                    `flex min-h-12 items-center gap-3 rounded-lg px-3 font-medium ${
                      isActive ? 'bg-slate-100 dark:bg-slate-800' : 'hover:bg-slate-100 dark:hover:bg-slate-800'
                    }`
                  }
                >
                  <Icon name={item.icon} />
                  {item.label}
                </NavLink>
              </li>
            ))}
            <li className="mt-1 border-t border-slate-200 pt-1 dark:border-slate-700">
              <button
                type="button"
                onClick={onLogout}
                disabled={loggingOut}
                className="flex min-h-12 w-full items-center gap-3 rounded-lg px-3 font-medium text-red-700 hover:bg-red-50 dark:text-red-400 dark:hover:bg-red-950"
              >
                <Icon name="logout" />
                Odjava
              </button>
            </li>
          </ul>
        </div>
      )}
    </>
  );
}
