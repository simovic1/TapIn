import type { RouteObject } from 'react-router';
import { AttendancePage } from './pages/AttendancePage';

export const attendanceRoutes: RouteObject[] = [
  { path: 'prozivka', element: <AttendancePage /> },
];
