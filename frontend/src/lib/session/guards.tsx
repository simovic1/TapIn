import type { ReactNode } from 'react';
import { Navigate, Outlet, useLocation } from 'react-router';
import { ErrorState } from '../../components/ui/ErrorState';
import { LoadingState } from '../../components/ui/LoadingState';
import { ForbiddenPage } from '../../pages/ForbiddenPage';
import { hasAnyRole, type Role } from './roles';
import { useCurrentUser } from './session';

export interface LoginRedirectState {
  from?: string;
}

/** Protects all signed-in screens. Unauthenticated users (incl. expired sessions) go to the login page. */
export function RequireAuth({ children }: { children?: ReactNode }) {
  const { data: user, isPending, isError, error, refetch } = useCurrentUser();
  const location = useLocation();

  if (isPending) return <LoadingState fullPage label="Provjera prijave…" />;
  if (isError) {
    return (
      <div className="mx-auto max-w-md p-4">
        <ErrorState error={error} onRetry={() => refetch()} />
      </div>
    );
  }
  if (!user) {
    const state: LoginRedirectState = { from: location.pathname + location.search };
    return <Navigate to="/prijava" replace state={state} />;
  }
  return <>{children ?? <Outlet />}</>;
}

/** Shows a 403 view when the signed-in user lacks every listed role. The backend enforces the same rule. */
export function RequireRole({ roles, children }: { roles: readonly Role[]; children: ReactNode }) {
  const { data: user } = useCurrentUser();
  if (!hasAnyRole(user, roles)) return <ForbiddenPage />;
  return <>{children}</>;
}
