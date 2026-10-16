import type { RouteObject } from 'react-router';
import { StatisticsPage } from './pages/StatisticsPage';

export const statisticsRoutes: RouteObject[] = [
  { path: 'statistika', element: <StatisticsPage /> },
];
