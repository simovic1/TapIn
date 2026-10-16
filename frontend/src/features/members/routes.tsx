import type { RouteObject } from 'react-router';
import { MembersPage } from './pages/MembersPage';

export const membersRoutes: RouteObject[] = [
  { path: 'clanovi', element: <MembersPage /> },
];
