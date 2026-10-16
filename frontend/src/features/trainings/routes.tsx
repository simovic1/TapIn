import type { RouteObject } from 'react-router';
import { RequireRole } from '../../lib/session/guards';
import { STAFF_ROLES } from '../../lib/session/roles';
import { SchedulePage } from './pages/SchedulePage';
import { TrainingDetailPage } from './pages/TrainingDetailPage';
import { EditTrainingPage, NewTrainingPage } from './pages/TrainingFormPage';
import { TrainingsPage } from './pages/TrainingsPage';

export const trainingsRoutes: RouteObject[] = [
  { path: 'treninzi', element: <TrainingsPage /> },
  {
    path: 'treninzi/novi',
    element: (
      <RequireRole roles={STAFF_ROLES}>
        <NewTrainingPage />
      </RequireRole>
    ),
  },
  {
    path: 'treninzi/raspored',
    element: (
      <RequireRole roles={STAFF_ROLES}>
        <SchedulePage />
      </RequireRole>
    ),
  },
  { path: 'treninzi/:trainingId', element: <TrainingDetailPage /> },
  {
    path: 'treninzi/:trainingId/uredi',
    element: (
      <RequireRole roles={STAFF_ROLES}>
        <EditTrainingPage />
      </RequireRole>
    ),
  },
];
