import { ROLE_LABELS, type Role } from '../../lib/session/roles';

export const selectClass = 'min-h-11 rounded-lg border bg-white px-3 text-base dark:bg-slate-950';

export function RoleFields({ roles, onChange, disabled = false }: { roles: Role[]; onChange: (roles: Role[]) => void; disabled?: boolean }) {
  return <fieldset disabled={disabled} className="flex flex-wrap gap-4 rounded-lg border p-3">
    <legend className="px-1 font-medium">Uloge (najmanje jedna)</legend>
    {(['MEMBER', 'TRAINER', 'ADMIN'] as Role[]).map(role => <label key={role} className="flex min-h-11 items-center gap-2">
      <input type="checkbox" checked={roles.includes(role)} onChange={event => onChange(event.target.checked ? [...roles, role] : roles.filter(item => item !== role))} />
      {ROLE_LABELS[role]}
    </label>)}
  </fieldset>;
}

export function MemberStatus({ accountStatus, membershipStatus }: { accountStatus: string; membershipStatus: string }) {
  return <div className="flex flex-wrap gap-2 text-sm">
    <span className={`rounded-full px-3 py-1 ${membershipStatus === 'ACTIVE' ? 'bg-green-100 text-green-900' : 'bg-slate-200 text-slate-900'}`}>
      {membershipStatus === 'ACTIVE' ? 'Aktivno članstvo' : 'Neaktivno članstvo'}
    </span>
    <span className={`rounded-full px-3 py-1 ${accountStatus === 'INVITED' ? 'bg-amber-100 text-amber-900' : 'bg-slate-100 text-slate-800'}`}>
      {accountStatus === 'INVITED' ? 'Pozivnica nije prihvaćena' : accountStatus === 'ACTIVE' ? 'Račun aktiviran' : 'Račun onemogućen'}
    </span>
  </div>;
}
