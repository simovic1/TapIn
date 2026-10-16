import type { RouteObject } from 'react-router';
import { TrainingsPage } from './pages/TrainingsPage';

export const trainingsRoutes: RouteObject[] = [
  { path: 'treninzi', element: <TrainingsPage /> },
];
