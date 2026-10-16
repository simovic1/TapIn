import type { RouteObject } from 'react-router';
import { RequireRole } from '../../lib/session/guards';
import { STAFF_ROLES } from '../../lib/session/roles';
import { AttendancePage } from './pages/AttendancePage';

export const attendanceRoutes: RouteObject[] = [
  {
    path: 'prozivka',
    element: (
      <RequireRole roles={STAFF_ROLES}>
        <AttendancePage />
      </RequireRole>
    ),
  },
];
