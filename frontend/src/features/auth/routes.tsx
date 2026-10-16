import type { RouteObject } from 'react-router';
import { LoginPage } from './pages/LoginPage';
import { AcceptInvitationPage } from './pages/AcceptInvitationPage';

export const authRoutes: RouteObject[] = [
  { path: 'prijava', element: <LoginPage /> },
  { path: 'pozivnica/:token', element: <AcceptInvitationPage /> },
];
