import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { Link } from 'react-router';
import { TextField } from '../../components/ui/TextField';
import { Button } from '../../components/ui/Button';
import { Alert } from '../../components/ui/Alert';
import { errorMessage } from '../../lib/api/errors';
import type { Role } from '../../lib/session/roles';
import { inviteMember, memberKeys } from './api';
import { RoleFields } from './MemberFields';

export function InviteMemberForm({ admin }: { admin: boolean }) {
  const client = useQueryClient();
  const [firstName, setFirstName] = useState('');
  const [lastName, setLastName] = useState('');
  const [email, setEmail] = useState('');
  const [joinedOn, setJoinedOn] = useState('');
  const [roles, setRoles] = useState<Role[]>(['MEMBER']);
  const [validation, setValidation] = useState('');
  const invite = useMutation({ mutationFn: inviteMember, onSuccess: () => client.invalidateQueries({ queryKey: memberKeys.all }) });
  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (invite.isPending) return;
    if (!firstName.trim() || !lastName.trim() || roles.length === 0) { setValidation('Unesite ime, prezime i odaberite najmanje jednu ulogu.'); return; }
    setValidation('');
    invite.mutate({ email: email.trim(), firstName: firstName.trim(), lastName: lastName.trim(), roles: admin ? roles : ['MEMBER'], ...(joinedOn ? { joinedOn } : {}) });
  };
  if (invite.isSuccess) return <div className="space-y-4 rounded-xl border p-4">
    <Alert tone="success">Pozivnica za {invite.data.email} je stavljena u red za slanje. Član će aktivirati račun putem poveznice u e-mailu.</Alert>
    <Link className="inline-flex min-h-11 items-center text-blue-700 underline" to={`/clanovi/${invite.data.membershipId}`}>Otvori profil pozvanog člana</Link>
    <Button onClick={() => { invite.reset(); setFirstName(''); setLastName(''); setEmail(''); setJoinedOn(''); setRoles(['MEMBER']); }}>Pozovi još jednog člana</Button>
  </div>;
  return <form onSubmit={submit} aria-label="Pozovi člana" className="space-y-4 rounded-xl border p-4">
    <h2 className="text-lg font-semibold">Pozovi novog člana</h2>
    {(validation || invite.isError) && <Alert tone="error">{validation || errorMessage(invite.error)}</Alert>}
    <div className="grid gap-4 sm:grid-cols-2">
      <TextField label="Ime" required maxLength={100} value={firstName} onChange={e => setFirstName(e.target.value)} />
      <TextField label="Prezime" required maxLength={100} value={lastName} onChange={e => setLastName(e.target.value)} />
      <TextField label="E-mail" required type="email" maxLength={320} value={email} onChange={e => setEmail(e.target.value)} />
      <TextField label="Datum učlanjenja" type="date" hint="Ako ostavite prazno, koristi se današnji datum." value={joinedOn} onChange={e => setJoinedOn(e.target.value)} />
    </div>
    {admin ? <RoleFields roles={roles} onChange={setRoles} /> : <p>Novi korisnik dobiva ulogu člana.</p>}
    <Button type="submit" loading={invite.isPending}>Pošalji pozivnicu</Button>
  </form>;
}
