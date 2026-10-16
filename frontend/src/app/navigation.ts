import { ADMIN_ROLES, ALL_ROLES, STAFF_ROLES, type Role } from '../lib/session/roles';

export type NavIcon = 'home' | 'calendar' | 'checklist' | 'users' | 'chart' | 'report' | 'user' | 'shield';

export interface NavItem {
  to: string;
  label: string;
  icon: NavIcon;
  /** The item is shown when the user has at least one of these roles. */
  roles: readonly Role[];
  /** Shown in the mobile bottom bar (max. 4 visible; the rest go to the "Više" menu). */
  primary: boolean;
}

/**
 * Single navigation for every user (spec §12.3): one account with several roles gets one menu whose items are the
 * union of what its roles allow. Order = importance on a phone.
 */
export const NAV_ITEMS: readonly NavItem[] = [
  { to: '/', label: 'Početna', icon: 'home', roles: ALL_ROLES, primary: true },
  { to: '/treninzi', label: 'Treninzi', icon: 'calendar', roles: ALL_ROLES, primary: true },
  { to: '/prozivka', label: 'Prozivka', icon: 'checklist', roles: STAFF_ROLES, primary: true },
  { to: '/statistika', label: 'Statistika', icon: 'chart', roles: ALL_ROLES, primary: true },
  { to: '/profil', label: 'Moj profil', icon: 'user', roles: ALL_ROLES, primary: false },
  { to: '/clanovi', label: 'Članovi', icon: 'users', roles: STAFF_ROLES, primary: false },
  { to: '/izvjestaji', label: 'Izvještaji', icon: 'report', roles: STAFF_ROLES, primary: false },
  { to: '/administracija', label: 'Administracija', icon: 'shield', roles: ADMIN_ROLES, primary: false },
];

export const MAX_BOTTOM_ITEMS = 4;

export function navigationFor(userRoles: readonly Role[]): NavItem[] {
  return NAV_ITEMS.filter((item) => item.roles.some((role) => userRoles.includes(role)));
}

/** Splits the allowed items into the mobile bottom bar and the overflow ("Više") menu. */
export function splitForMobile(items: readonly NavItem[]): { bar: NavItem[]; more: NavItem[] } {
  const primary = items.filter((item) => item.primary);
  const bar = primary.slice(0, MAX_BOTTOM_ITEMS);
  return { bar, more: items.filter((item) => !bar.includes(item)) };
}
