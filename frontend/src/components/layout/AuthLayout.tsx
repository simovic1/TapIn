import { Link, Outlet } from 'react-router';

/** Minimal shell for unauthenticated screens (login, invitation, password reset). */
export function AuthLayout() {
  return (
    <div className="flex min-h-dvh flex-col items-center justify-center px-4 py-10">
      <Link to="/" className="mb-6 text-2xl font-bold tracking-tight">
        TapIn
      </Link>
      <main className="w-full max-w-sm rounded-2xl border border-slate-200 bg-white p-6 shadow-sm dark:border-slate-800 dark:bg-slate-900">
        <Outlet />
      </main>
    </div>
  );
}
