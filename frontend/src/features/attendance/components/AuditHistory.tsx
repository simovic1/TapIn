import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { apiFetch } from '../../../lib/api/client';
import type { Schemas } from '../../../lib/api/types';
import { formatDateTime } from '../../../lib/format';
import { QueryState } from '../../../components/ui/QueryState';
import { Button } from '../../../components/ui/Button';
import type { RosterEntry } from '../api';
import { ATTENDANCE_LABELS } from './RosterRow';

const sources = { SINGLE: 'Pojedinačni unos', BULK: 'Masovno označavanje', HISTORICAL_IMPORT: 'Povijesni unos', CORRECTION: 'Ispravak' };

export function AuditHistory({ trainingId, entries }: { trainingId: number; entries: RosterEntry[] }) {
  const [open, setOpen] = useState(false);
  const [member, setMember] = useState('');
  const [page, setPage] = useState(0);
  const audit = useQuery({
    queryKey: ['attendance', 'audit', trainingId, member, page, entries],
    queryFn: () => apiFetch<Schemas['AttendanceAuditPage']>(`/trainings/${trainingId}/attendance/audit?page=${page}&size=20${member ? `&membershipId=${member}` : ''}`),
    enabled: open,
  });
  return <section className="mt-6 rounded-xl border p-4">
    <h2 className="mb-3 text-lg font-semibold">Revizijska povijest prisutnosti</h2>
    <Button variant="secondary" onClick={() => setOpen(!open)}>{open ? 'Sakrij povijest' : 'Prikaži povijest'}</Button>
    {open && <div className="mt-4 space-y-4">
      <p>Najnovije promjene su prve. Vrijeme je prikazano u zoni Europe/Zagreb. Povijest je samo za čitanje.</p>
      <label className="block">Član u povijesti <select className="rounded border p-2" value={member} onChange={e => { setMember(e.target.value); setPage(0); }}>
        <option value="">Svi članovi</option>
        {entries.map(e => <option key={e.membershipId} value={e.membershipId}>{e.firstName} {e.lastName}</option>)}
      </select></label>
      <Button variant="ghost" onClick={() => void audit.refetch()}>Osvježi povijest</Button>
      <QueryState query={audit}>{data => <>
        {data.items.length === 0 && <p>Nema promjena za odabrani filtar.</p>}
        <ol className="space-y-3">{data.items.map(e => <li key={e.auditId} className="rounded border p-3">
          <p className="font-semibold">{e.memberName ?? `Član #${e.membershipId}`}: {ATTENDANCE_LABELS[e.previousStatus]} → {ATTENDANCE_LABELS[e.newStatus]}</p>
          <p>{formatDateTime(e.occurredAt)} · Autor: {e.actorName ?? `Korisnik #${e.actorUserId}`} (#{e.actorUserId})</p>
          <p>{sources[e.source]} · {e.source}</p>
          {e.note && <p className="whitespace-pre-wrap">{e.note}</p>}
        </li>)}</ol>
        <div className="flex items-center gap-3">
          <Button disabled={page === 0} onClick={() => setPage(page - 1)}>Prethodna</Button>
          <span>Stranica {page + 1}</span>
          <Button disabled={page + 1 >= data.page.totalPages} onClick={() => setPage(page + 1)}>Sljedeća</Button>
        </div>
      </>}</QueryState>
    </div>}
  </section>;
}
