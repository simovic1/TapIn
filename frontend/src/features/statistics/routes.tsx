import type { RouteObject } from 'react-router';
import { RequireRole } from '../../lib/session/guards';
import { STAFF_ROLES } from '../../lib/session/roles';
import { ReportsPage } from './pages/ReportsPage';
import { StatisticsPage } from './pages/StatisticsPage';

export const statisticsRoutes: RouteObject[] = [
  { path: 'statistika', element: <StatisticsPage /> },
  {
    path: 'izvjestaji',
    element: (
      <RequireRole roles={STAFF_ROLES}>
        <ReportsPage />
      </RequireRole>
    ),
  },
];
