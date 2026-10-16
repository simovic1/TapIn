import type { RouteObject } from 'react-router';
import { AcceptInvitationPage } from './pages/AcceptInvitationPage';
import { ForgotPasswordPage } from './pages/ForgotPasswordPage';
import { LoginPage } from './pages/LoginPage';

/** Public routes (rendered without the signed-in layout). */
export const authRoutes: RouteObject[] = [
  { path: 'prijava', element: <LoginPage /> },
  { path: 'pozivnica/:token', element: <AcceptInvitationPage /> },
  { path: 'zaboravljena-lozinka', element: <ForgotPasswordPage /> },
];
