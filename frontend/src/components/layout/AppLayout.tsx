import { NavLink, Outlet } from 'react-router';

const navItems = [
  { to: '/', label: 'Početna', end: true },
  { to: '/treninzi', label: 'Treninzi' },
  { to: '/prozivka', label: 'Prozivka' },
  { to: '/clanovi', label: 'Članovi' },
  { to: '/statistika', label: 'Statistika' },
];

const linkClass = ({ isActive }: { isActive: boolean }) =>
  `rounded-lg px-3 py-2 text-sm font-medium whitespace-nowrap ${
    isActive
      ? 'bg-slate-900 text-white dark:bg-white dark:text-slate-900'
      : 'text-slate-600 hover:bg-slate-200 dark:text-slate-300 dark:hover:bg-slate-800'
  }`;

export function AppLayout() {
  return (
    <div className="min-h-dvh">
      <header className="sticky top-0 z-10 border-b border-slate-200 bg-white/90 backdrop-blur dark:border-slate-800 dark:bg-slate-900/90">
        <div className="mx-auto flex max-w-5xl items-center gap-4 px-4 py-3">
          <NavLink to="/" className="text-lg font-bold tracking-tight">
            TapIn
          </NavLink>
          <nav className="-mx-1 flex flex-1 gap-1 overflow-x-auto px-1" aria-label="Glavna navigacija">
            {navItems.map((item) => (
              <NavLink key={item.to} to={item.to} end={item.end} className={linkClass}>
                {item.label}
              </NavLink>
            ))}
          </nav>
          <NavLink to="/prijava" className="text-sm text-slate-600 hover:underline dark:text-slate-300">
            Prijava
          </NavLink>
        </div>
      </header>
      <main className="mx-auto max-w-5xl px-4 py-6">
        <Outlet />
      </main>
    </div>
  );
}
