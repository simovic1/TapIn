import { useQuery } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { Link } from 'react-router';
import { PageHeader } from '../../../components/ui/PageHeader';
import { QueryState } from '../../../components/ui/QueryState';
import { TextField } from '../../../components/ui/TextField';
import { Button } from '../../../components/ui/Button';
import { useCurrentUser } from '../../../lib/session/session';
import { ROLE_LABELS, type Role } from '../../../lib/session/roles';
import { listMembers, memberKeys } from '../api';
import { MemberStatus, selectClass } from '../MemberFields';
import { InviteMemberForm } from '../InviteMemberForm';

export function MembersPage({ administration = false }: { administration?: boolean }) {
  const { data: user } = useCurrentUser();
  const [search, setSearch] = useState('');
  const [q, setQ] = useState('');
  const [status, setStatus] = useState('');
  const [role, setRole] = useState('');
  const [sort, setSort] = useState('lastName,asc');
  const [page, setPage] = useState(0);
  const [inviting, setInviting] = useState(false);
  const params = new URLSearchParams({ q, page: String(page), size: '20', sort });
  if (status) params.set('membershipStatus', status);
  if (role) params.set('role', role);
  const members = useQuery({ queryKey: [...memberKeys.all, 'list', params.toString()], queryFn: () => listMembers(params) });
  const submit = (event: FormEvent) => { event.preventDefault(); setQ(search.trim()); setPage(0); };
  return (
    <>
      <PageHeader title={administration ? 'Administracija članstva' : 'Članovi'}
        description={administration ? 'Otvorite člana za izmjenu podataka, uloga i aktivnosti.' : 'Pronađite člana ili pošaljite pozivnicu.'}
        actions={<Button onClick={() => setInviting(!inviting)}>{inviting ? 'Zatvori pozivanje' : 'Pozovi člana'}</Button>} />
      {inviting && <div className="mb-6"><InviteMemberForm admin={!!user?.roles.includes('ADMIN')} /></div>}
      <form onSubmit={submit} className="mb-6 grid items-end gap-3 sm:grid-cols-2 lg:grid-cols-5" aria-label="Pretraga članova">
        <TextField label="Pretraga" placeholder="Ime, prezime ili e-mail" value={search} maxLength={100} onChange={e => setSearch(e.target.value)} />
        <label className="flex flex-col gap-1">Članstvo<select className={selectClass} value={status} onChange={e => { setStatus(e.target.value); setPage(0); }}>
          <option value="">Sva članstva</option><option value="ACTIVE">Aktivna</option><option value="INACTIVE">Neaktivna</option>
        </select></label>
        <label className="flex flex-col gap-1">Uloga<select className={selectClass} value={role} onChange={e => { setRole(e.target.value); setPage(0); }}>
          <option value="">Sve uloge</option>{Object.entries(ROLE_LABELS).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
        </select></label>
        <label className="flex flex-col gap-1">Redoslijed<select className={selectClass} value={sort} onChange={e => { setSort(e.target.value); setPage(0); }}>
          <option value="lastName,asc">Prezime A–Ž</option><option value="lastName,desc">Prezime Ž–A</option><option value="joinedOn,desc">Najnoviji članovi</option><option value="joinedOn,asc">Najstariji članovi</option>
        </select></label>
        <Button type="submit">Pretraži</Button>
      </form>
      <QueryState query={members} isEmpty={data => data.items.length === 0} empty={{ title: 'Nema članova za odabranu pretragu' }}>
        {data => <>
          <p className="mb-3 text-sm">Ukupno članova: {data.page.totalElements}</p>
          <ul className="grid gap-3 md:grid-cols-2">
            {data.items.map(member => <li key={member.membershipId} className="space-y-2 rounded-xl border p-4">
              <Link className="inline-flex min-h-11 items-center text-lg font-semibold text-blue-700 underline" to={`/clanovi/${member.membershipId}`}>{member.firstName} {member.lastName}</Link>
              <p className="break-all">{member.email}</p>
              <MemberStatus accountStatus={member.accountStatus} membershipStatus={member.membershipStatus} />
              <p className="text-sm">{member.roles.map(role => ROLE_LABELS[role as Role]).join(', ')} · Član od {new Date(member.joinedOn + 'T12:00:00').toLocaleDateString('hr-HR')}</p>
            </li>)}
          </ul>
          <nav aria-label="Stranice imenika" className="mt-5 flex items-center justify-between gap-3">
            <Button disabled={page === 0} onClick={() => setPage(page - 1)}>Prethodna</Button>
            <span>Stranica {page + 1} od {data.page.totalPages}</span>
            <Button disabled={page + 1 >= data.page.totalPages} onClick={() => setPage(page + 1)}>Sljedeća</Button>
          </nav>
        </>}
      </QueryState>
    </>
  );
}
