import { createBrowserRouter } from 'react-router';
import { AppLayout } from '../components/layout/AppLayout';
import { AuthLayout } from '../components/layout/AuthLayout';
import { HomePage } from '../pages/HomePage';
import { NotFoundPage } from '../pages/NotFoundPage';
import { authRoutes } from '../features/auth/routes';
import { trainingsRoutes } from '../features/trainings/routes';
import { attendanceRoutes } from '../features/attendance/routes';
import { membersRoutes } from '../features/members/routes';
import { beltsRoutes } from '../features/belts/routes';
import { statisticsRoutes } from '../features/statistics/routes';

export const router = createBrowserRouter([
  { element: <AuthLayout />, children: authRoutes },
  {
    element: <AppLayout />,
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
]);
