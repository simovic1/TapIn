import { createBrowserRouter, type RouteObject } from 'react-router';
import { AppLayout } from '../components/layout/AppLayout';
import { AuthLayout } from '../components/layout/AuthLayout';
import { RequireAuth } from '../lib/session/guards';
import { HomePage } from '../pages/HomePage';
import { NotFoundPage } from '../pages/NotFoundPage';
import { authRoutes } from '../features/auth/routes';
import { trainingsRoutes } from '../features/trainings/routes';
import { attendanceRoutes } from '../features/attendance/routes';
import { membersRoutes } from '../features/members/routes';
import { beltsRoutes } from '../features/belts/routes';
import { statisticsRoutes } from '../features/statistics/routes';

export const routes: RouteObject[] = [
  { element: <AuthLayout />, children: authRoutes },
  {
    // Everything else requires a session; role checks happen per route (RequireRole) and on the backend.
    element: (
      <RequireAuth>
        <AppLayout />
      </RequireAuth>
    ),
    children: [
      { index: true, element: <HomePage /> },
      ...trainingsRoutes,
      ...attendanceRoutes,
      ...membersRoutes,
      ...beltsRoutes,
      ...statisticsRoutes,
      { path: '*', element: <NotFoundPage /> },
    ],
  },
];

export const router = createBrowserRouter(routes);
