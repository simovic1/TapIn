import type { RouteObject } from 'react-router';
import { TrainingDetailPage } from './pages/TrainingDetailPage';
import { TrainingsPage } from './pages/TrainingsPage';

export const trainingsRoutes: RouteObject[] = [
  { path: 'treninzi', element: <TrainingsPage /> },
  { path: 'treninzi/:trainingId', element: <TrainingDetailPage /> },
];
