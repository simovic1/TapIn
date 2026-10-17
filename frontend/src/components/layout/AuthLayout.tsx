import { Link, Outlet } from 'react-router';
import { useSystemInfo } from '../../lib/api/system';
import { BeltMark } from './BeltMark';

/**
 * Minimal shell for unauthenticated screens (login, invitation, password reset). The card is tied like a black
 * belt: an ink band with the club's red rank bar across its top.
 */
export function AuthLayout() {
  const system = useSystemInfo();
  return (
    <div className="flex min-h-dvh flex-col items-center justify-center px-4 py-10">
      <div className="w-full max-w-sm overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-sm dark:border-slate-800 dark:bg-slate-900">
        <Link to="/" aria-label="TapIn – početna" className="flex bg-ink">
          <BeltMark size="hero" club={system.data?.clubName} />
        </Link>
        <main className="p-6">
          <Outlet />
        </main>
      </div>
    </div>
  );
}
