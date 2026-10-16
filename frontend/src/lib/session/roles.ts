import type { Schemas } from '../api/types';

export type Role = Schemas['Role'];
export type CurrentUser = Schemas['CurrentUser'];

/** Role sets shared by navigation and route guards (spec §2.2), so both always agree. */
export const ALL_ROLES: readonly Role[] = ['MEMBER', 'TRAINER', 'ADMIN'];
export const STAFF_ROLES: readonly Role[] = ['TRAINER', 'ADMIN'];
export const ADMIN_ROLES: readonly Role[] = ['ADMIN'];

export const ROLE_LABELS: Record<Role, string> = {
  MEMBER: 'Član',
  TRAINER: 'Trener',
  ADMIN: 'Administrator',
};

/** Roles are additive: a user may hold several and gets the union of their permissions. */
export function hasAnyRole(user: Pick<CurrentUser, 'roles'> | null | undefined, roles: readonly Role[]): boolean {
  return !!user && user.roles.some((role) => roles.includes(role));
}
