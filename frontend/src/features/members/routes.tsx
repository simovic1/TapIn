import type { RouteObject } from 'react-router';
import { RequireRole } from '../../lib/session/guards';
import { ADMIN_ROLES, STAFF_ROLES } from '../../lib/session/roles';
import { AdministrationPage } from './pages/AdministrationPage';
import { MembersPage } from './pages/MembersPage';

export const membersRoutes: RouteObject[] = [
  {
    path: 'clanovi',
    element: (
      <RequireRole roles={STAFF_ROLES}>
        <MembersPage />
      </RequireRole>
    ),
  },
  {
    path: 'administracija',
    element: (
      <RequireRole roles={ADMIN_ROLES}>
        <AdministrationPage />
      </RequireRole>
    ),
  },
];
