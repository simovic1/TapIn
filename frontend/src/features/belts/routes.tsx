import type { RouteObject } from 'react-router';
import { ProfilePage } from './pages/ProfilePage';

/** "Moj profil" is mostly the belt history (spec §12.1), so it lives in the belts feature. */
export const beltsRoutes: RouteObject[] = [{ path: 'profil', element: <ProfilePage /> }];
