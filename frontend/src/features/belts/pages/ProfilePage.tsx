import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { PageHeader } from '../../../components/ui/PageHeader';
import { QueryState } from '../../../components/ui/QueryState';
import { TextField } from '../../../components/ui/TextField';
import { Button } from '../../../components/ui/Button';
import { Alert } from '../../../components/ui/Alert';
import { apiFetch } from '../../../lib/api/client';
import { errorMessage } from '../../../lib/api/errors';
import { ROLE_LABELS, type CurrentUser } from '../../../lib/session/roles';
import { sessionKeys, useCurrentUser } from '../../../lib/session/session';
import { useBeltHistory } from '../api';
import { BeltHistoryView } from '../components/BeltHistoryView';

/** Own belt history with trainings per belt; read-only - only a trainer changes belts (AC-28). */
function OwnBelts() {
  const history = useBeltHistory('me');
  return <QueryState query={history} loadingLabel="Učitavanje pojaseva…">{data => <BeltHistoryView history={data} />}</QueryState>;
}

export function ProfilePage() {
  const user = useCurrentUser();
  return (
    <><PageHeader title="Moj profil" /><QueryState query={user}>{data => data && <OwnProfile key={data.userId} initial={data} />}</QueryState></>
  );
}

function OwnProfile({ initial }: { initial: CurrentUser }) {
  const client = useQueryClient();
  const [user, setUser] = useState(initial);
  const [phone, setPhone] = useState(initial.phone ?? '');
  const save = useMutation({ mutationFn: () => apiFetch<CurrentUser>('/me/profile', {
    method: 'PATCH', body: JSON.stringify({ expectedVersion: user.version, phone: phone.trim() || null }),
  }), onSuccess: value => { setUser(value); client.setQueryData(sessionKeys.currentUser, value); } });
  const reload = useMutation({ mutationFn: () => apiFetch<CurrentUser>('/me'), onSuccess: value => {
    setUser(value); setPhone(value.phone ?? ''); client.setQueryData(sessionKeys.currentUser, value); save.reset();
  } });
  const submit = (event: FormEvent) => { event.preventDefault(); if (!save.isPending) save.mutate(); };
  return <div className="max-w-2xl space-y-6">
    <dl className="grid gap-4 sm:grid-cols-2">
      <div><dt>Ime i prezime</dt><dd className="font-semibold">{user.firstName} {user.lastName}</dd></div>
      <div><dt>E-mail</dt><dd className="break-all">{user.email}</dd></div>
      <div><dt>Datum učlanjenja</dt><dd>{new Date(user.joinedOn + 'T12:00:00').toLocaleDateString('hr-HR')}</dd></div>
      <div><dt>Članstvo</dt><dd>{user.membershipStatus === 'ACTIVE' ? 'Aktivno' : 'Neaktivno'}</dd></div>
      <div><dt>Uloge</dt><dd>{user.roles.map(role => ROLE_LABELS[role]).join(', ')}</dd></div>
    </dl>
    <form onSubmit={submit} className="space-y-4 rounded-xl border p-4" aria-label="Vlastiti telefon">
      <TextField label="Telefon" type="tel" maxLength={32} value={phone} onChange={e => setPhone(e.target.value)} hint="Neobavezno. Za izmjene ostalih podataka obratite se administratoru." />
      {(save.isError || reload.isError) && <Alert tone="error">{errorMessage(save.error ?? reload.error)}</Alert>}
      {save.isSuccess && <Alert tone="success">Telefon je spremljen.</Alert>}
      <Button type="submit" loading={save.isPending} disabled={reload.isPending}>Spremi telefon</Button>
      {save.isError && <Button onClick={() => reload.mutate()} disabled={save.isPending || reload.isPending}>Učitaj spremljene podatke i odbaci unos</Button>}
    </form>
    <section className="space-y-3" aria-label="Pojas"><h2 className="text-lg font-semibold">Pojas i povijest pojaseva</h2><OwnBelts /></section>
  </div>;
}
