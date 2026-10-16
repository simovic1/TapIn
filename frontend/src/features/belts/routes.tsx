import type { RouteObject } from 'react-router';
import { RequireRole } from '../../lib/session/guards';
import { STAFF_ROLES } from '../../lib/session/roles';
import { MemberBeltsPage } from './pages/MemberBeltsPage';
import { ProfilePage } from './pages/ProfilePage';

/** "Moj profil" is mostly the belt history (spec §12.1), so it lives in the belts feature. */
export const beltsRoutes: RouteObject[] = [
  { path: 'profil', element: <ProfilePage /> },
  {
    path: 'clanovi/:membershipId/pojasevi',
    element: (
      <RequireRole roles={STAFF_ROLES}>
        <MemberBeltsPage />
      </RequireRole>
    ),
  },
];
