import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { Link, useParams } from 'react-router';
import { PageHeader } from '../../../components/ui/PageHeader';
import { QueryState } from '../../../components/ui/QueryState';
import { Alert } from '../../../components/ui/Alert';
import { Button } from '../../../components/ui/Button';
import { TextField } from '../../../components/ui/TextField';
import { errorMessage } from '../../../lib/api/errors';
import { sessionKeys, useCurrentUser } from '../../../lib/session/session';
import { ROLE_LABELS, type Role } from '../../../lib/session/roles';
import { getMember, memberKeys, replaceRoles, resendInvitation, updateMember, type Member } from '../api';
import { MemberStatus, RoleFields, selectClass } from '../MemberFields';

const BELT_NAMES: Record<Member['currentBelt'], string> = {
  WHITE: 'Bijeli', BLUE: 'Plavi', PURPLE: 'Ljubičasti', BROWN: 'Smeđi', BLACK: 'Crni', UNKNOWN: 'Nepoznat',
};

export function MemberDetailPage() {
  const id = Number(useParams().membershipId);
  const member = useQuery({ queryKey: memberKeys.detail(id), queryFn: () => getMember(id) });
  return <>
    <Link to="/clanovi" className="mb-4 inline-flex min-h-11 items-center text-blue-700 underline">Natrag na imenik</Link>
    <QueryState query={member}>{data => <MemberPanel key={data.membershipId} initial={data} />}</QueryState>
  </>;
}

function MemberPanel({ initial }: { initial: Member }) {
  const client = useQueryClient();
  const { data: user } = useCurrentUser();
  const admin = !!user?.roles.includes('ADMIN');
  const [member, setMember] = useState(initial);
  const [firstName, setFirstName] = useState(initial.firstName);
  const [lastName, setLastName] = useState(initial.lastName);
  const [email, setEmail] = useState(initial.email);
  const [phone, setPhone] = useState(initial.phone ?? '');
  const [joinedOn, setJoinedOn] = useState(initial.joinedOn);
  const [membershipStatus, setMembershipStatus] = useState(initial.membershipStatus);
  const [roles, setRoles] = useState<Role[]>(initial.roles);
  const [notice, setNotice] = useState('');
  const [validation, setValidation] = useState('');
  const saved = (value: Member) => {
    setMember(value);
    setNotice('Promjene su spremljene.');
    client.setQueryData(memberKeys.detail(value.membershipId), value);
    void client.invalidateQueries({ queryKey: [...memberKeys.all, 'list'] });
    void client.invalidateQueries({ queryKey: sessionKeys.currentUser });
  };
  const edit = useMutation({ mutationFn: () => updateMember(member.membershipId, {
    expectedVersion: member.version, firstName: firstName.trim(), lastName: lastName.trim(), email: email.trim(),
    phone: phone.trim() || null, joinedOn, membershipStatus,
  }), onSuccess: saved });
  const roleEdit = useMutation({ mutationFn: () => replaceRoles(member.membershipId, { expectedVersion: member.version, roles }), onSuccess: saved });
  const resend = useMutation({ mutationFn: () => resendInvitation(member.membershipId), onSuccess: () => setNotice('Nova pozivnica je stavljena u red za slanje. Prethodna poveznica više ne vrijedi.') });
  const reload = useMutation({ mutationFn: () => getMember(member.membershipId), onSuccess: value => {
    setMember(value); setFirstName(value.firstName); setLastName(value.lastName); setEmail(value.email);
    setPhone(value.phone ?? ''); setJoinedOn(value.joinedOn); setMembershipStatus(value.membershipStatus); setRoles(value.roles);
    edit.reset(); roleEdit.reset(); setValidation(''); setNotice('Učitani su posljednji spremljeni podaci.');
  } });
  const busy = edit.isPending || roleEdit.isPending || reload.isPending;
  const submit = (event: FormEvent) => { event.preventDefault(); if (busy) return; setNotice(''); edit.mutate(); };
  const submitRoles = (event: FormEvent) => {
    event.preventDefault(); if (busy) return;
    if (!roles.length) { setValidation('Odaberite najmanje jednu ulogu.'); return; }
    setValidation(''); setNotice(''); roleEdit.mutate();
  };
  return <div className="space-y-6">
    <PageHeader title={`${member.firstName} ${member.lastName}`} description={member.email} />
    <MemberStatus accountStatus={member.accountStatus} membershipStatus={member.membershipStatus} />
    {notice && <Alert tone="success">{notice}</Alert>}
    {(edit.isError || roleEdit.isError || resend.isError || reload.isError || validation) && <Alert tone="error">{validation || errorMessage(edit.error ?? roleEdit.error ?? resend.error ?? reload.error)}</Alert>}
    {member.accountStatus === 'INVITED' && <div className="space-y-2">
      <p>Član još nije aktivirao račun. Aktivnost članstva ne označava prihvat pozivnice.</p>
      <Button onClick={() => resend.mutate()} loading={resend.isPending} disabled={busy}>Ponovno pošalji pozivnicu</Button>
    </div>}
    {admin ? <>
      <form onSubmit={submit} aria-label="Osnovni podaci člana" className="space-y-4 rounded-xl border p-4">
        <h2 className="text-lg font-semibold">Osnovni podaci i članstvo</h2>
        <div className="grid gap-4 sm:grid-cols-2">
          <TextField label="Ime" required maxLength={100} value={firstName} onChange={e => setFirstName(e.target.value)} />
          <TextField label="Prezime" required maxLength={100} value={lastName} onChange={e => setLastName(e.target.value)} />
          <TextField label="E-mail" required type="email" maxLength={320} value={email} onChange={e => setEmail(e.target.value)} />
          <TextField label="Telefon" type="tel" maxLength={32} value={phone} onChange={e => setPhone(e.target.value)} />
          <TextField label="Datum učlanjenja" type="date" required value={joinedOn} onChange={e => setJoinedOn(e.target.value)} />
          <label className="flex flex-col gap-1">Aktivnost članstva<select className={selectClass} value={membershipStatus} onChange={e => setMembershipStatus(e.target.value as Member['membershipStatus'])}>
            <option value="ACTIVE">Aktivno</option><option value="INACTIVE">Neaktivno</option>
          </select></label>
        </div>
        <p className="text-sm">Promjena e-maila poništava postojeće aktivacijske i reset poveznice. Za neaktiviran račun nakon spremanja pošaljite novu pozivnicu.</p>
        <Button type="submit" disabled={busy} loading={edit.isPending}>Spremi podatke</Button>
      </form>
      <form onSubmit={submitRoles} aria-label="Uloge člana" className="space-y-4 rounded-xl border p-4">
        <RoleFields roles={roles} onChange={setRoles} disabled={busy} />
        <Button type="submit" disabled={busy} loading={roleEdit.isPending}>Spremi uloge</Button>
      </form>
      <Button onClick={() => reload.mutate()} disabled={busy}>Ponovno učitaj spremljene podatke</Button>
      <p className="text-sm">Ponovno učitavanje zamijenit će vaše nespremljene unose.</p>
    </> : <dl className="grid gap-3 rounded-xl border p-4 sm:grid-cols-2">
      <div><dt className="text-sm text-slate-500">Telefon</dt><dd>{member.phone || 'Nije naveden'}</dd></div>
      <div><dt className="text-sm text-slate-500">Član od</dt><dd>{new Date(member.joinedOn + 'T12:00:00').toLocaleDateString('hr-HR')}</dd></div>
      <div><dt className="text-sm text-slate-500">Uloge</dt><dd>{member.roles.map(role => ROLE_LABELS[role]).join(', ')}</dd></div>
    </dl>}
    <section className="space-y-2 rounded-xl border p-4" aria-label="Pojas"><h2 className="text-lg font-semibold">Pojas</h2>
      <p>{member.currentBelt === 'UNKNOWN' ? 'Pojas nije evidentiran.' : `${BELT_NAMES[member.currentBelt]} pojas, dodijeljen ${member.currentBeltAwardedOn ? new Date(member.currentBeltAwardedOn + 'T12:00:00').toLocaleDateString('hr-HR') : '(datum nije evidentiran)'}`}</p>
      <Link to={`/clanovi/${member.membershipId}/pojasevi`} className="inline-flex min-h-11 items-center text-blue-700 underline">Povijest pojaseva, promocije i ispravci</Link>
    </section>
    <section className="space-y-3 rounded-xl border p-4"><h2 className="text-lg font-semibold">Povijest aktivnosti članstva</h2>
      {member.statusHistory?.length ? <ol className="space-y-2">{member.statusHistory.map((event, index) => <li key={`${event.effectiveAt}-${index}`}>
        <time dateTime={event.effectiveAt}>{new Date(event.effectiveAt).toLocaleString('hr-HR', { timeZone: 'Europe/Zagreb' })}</time>
        {' — '}{event.newStatus === 'ACTIVE' ? 'Aktivno članstvo' : 'Neaktivno članstvo'}{event.oldStatus === null && ' (početno stanje)'}
      </li>)}</ol> : <p>Povijest još nije evidentirana.</p>}
    </section>
  </div>;
}
