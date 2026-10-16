import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Link, useParams } from 'react-router';
import { Alert } from '../../../components/ui/Alert';
import { Button } from '../../../components/ui/Button';
import { PageHeader } from '../../../components/ui/PageHeader';
import { QueryState } from '../../../components/ui/QueryState';
import { TextField } from '../../../components/ui/TextField';
import { ApiError } from '../../../lib/api/client';
import { errorMessage } from '../../../lib/api/errors';
import { formatLongDate, formatTimeRange } from '../../../lib/format';
import {
  attendanceKeys,
  setAttendance,
  useApplyRosterEntry,
  useMarkUnconfirmedAbsent,
  useMemberSearch,
  useRoster,
  type Roster,
  type RosterEntry,
} from '../api';
import { RosterRow } from '../components/RosterRow';

/** Trainer's roll call (spec §6.2, §12.2): counters, one row per member, adding walk-ins and the bulk action. */
export function RosterPage() {
  const trainingId = Number(useParams().trainingId);
  const roster = useRoster(trainingId);
  return (
    <section className="max-w-3xl">
      <p className="mb-2 text-sm">
        <Link to="/prozivka" className="underline underline-offset-2">
          ← Odabir treninga
        </Link>
      </p>
      <QueryState query={roster} loadingLabel="Učitavanje prozivke…">
        {(data) => <RosterView trainingId={trainingId} roster={data} />}
      </QueryState>
    </section>
  );
}

function RosterView({ trainingId, roster }: { trainingId: number; roster: Roster }) {
  const apply = useApplyRosterEntry(trainingId);
  const t = roster.training;
  const cancelled = t.status === 'CANCELLED';
  const started = Date.parse(t.startAt) <= Date.now();
  const onRoster = new Set(roster.entries.map((e) => e.membershipId));

  return (
    <>
      <PageHeader
        title={`Prozivka: ${t.title ?? 'Trening'}`}
        description={`${formatLongDate(t.startAt)}, ${formatTimeRange(t.startAt, t.endAt)}${
          t.coaches.length ? ` · ${t.coaches.map((c) => `${c.firstName} ${c.lastName}`).join(', ')}` : ''
        }`}
      />
      {cancelled && (
        <div className="mb-4">
          <Alert tone="error" title="Trening je otkazan">Dolasci se ne evidentiraju.</Alert>
        </div>
      )}
      <Counters roster={roster} />
      <p className="mb-3 text-sm text-slate-600 dark:text-slate-400">
        Svaki odabir sprema se odmah; nema gumba „Spremi”. Treneri na treningu su informativni – svaki trener može evidentirati.
      </p>

      {roster.entries.length === 0 ? (
        <p className="mb-4 rounded-xl border border-dashed border-slate-300 p-4 text-center text-sm dark:border-slate-700">
          Nitko se još nije prijavio. Članove koji su došli dodajte pretragom ispod.
        </p>
      ) : (
        <ul className="mb-6 flex flex-col gap-2" aria-label="Članovi na prozivci">
          {roster.entries.map((entry) => (
            <RosterRow key={entry.membershipId} trainingId={trainingId} entry={entry} disabled={cancelled} onConfirmed={apply} />
          ))}
        </ul>
      )}

      {!cancelled && <AddMember trainingId={trainingId} onRoster={onRoster} />}
      {!cancelled && <BulkAbsent trainingId={trainingId} roster={roster} started={started} />}
    </>
  );
}

function Counters({ roster }: { roster: Roster }) {
  const c = roster.counts;
  const items = [
    ['Prijavljeni', c.registered],
    ['Prisutni', c.present],
    ['Odsutni', c.absent],
    ['Nepotvrđeni', c.unmarked],
  ] as const;
  return (
    <dl className="mb-4 grid grid-cols-4 gap-2" aria-label="Brojači">
      {items.map(([label, value]) => (
        <div key={label} className="rounded-xl border border-slate-200 bg-white p-2 text-center dark:border-slate-800 dark:bg-slate-900">
          <dt className="text-xs text-slate-600 dark:text-slate-400">{label}</dt>
          <dd className="text-xl font-semibold tabular-nums">{value}</dd>
        </div>
      ))}
    </dl>
  );
}

/** Walk-in (AC-06): search active members and mark them present directly. */
function AddMember({ trainingId, onRoster }: { trainingId: number; onRoster: Set<number> }) {
  const [q, setQ] = useState('');
  const [busy, setBusy] = useState<number>();
  const [message, setMessage] = useState<{ tone: 'success' | 'error' | 'warning'; text: string }>();
  const search = useMemberSearch(q);
  const queryClient = useQueryClient();
  const results = (search.data ?? []).filter((m) => !onRoster.has(m.membershipId));

  const add = (membershipId: number, name: string) => {
    setBusy(membershipId);
    setMessage(undefined);
    setAttendance(trainingId, membershipId, 'PRESENT', 0)
      .then(
        () => setMessage({ tone: 'success', text: `${name} je dodan(a) kao prisutan/prisutna.` }),
        (error: unknown) =>
          setMessage(
            error instanceof ApiError && error.status === 409
              ? { tone: 'warning', text: `${name} je u međuvremenu već evidentiran(a); prozivka je osvježena.` }
              : { tone: 'error', text: `Dodavanje nije spremljeno. ${errorMessage(error)}` },
          ),
      )
      .finally(() => {
        setBusy(undefined);
        void queryClient.invalidateQueries({ queryKey: attendanceKeys.roster(trainingId) });
      });
  };

  return (
    <section aria-labelledby="add-member" className="mb-6 rounded-2xl border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900">
      <h2 id="add-member" className="mb-2 font-semibold">
        Dodaj člana koji nije prijavljen
      </h2>
      <TextField label="Pretraži članove" value={q} onChange={(e) => setQ(e.target.value)} hint="Ime, prezime ili e-mail (najmanje 2 znaka)." />
      {message && (
        <div className="mt-3">
          <Alert tone={message.tone}>{message.text}</Alert>
        </div>
      )}
      {search.isError && (
        <div className="mt-3">
          <Alert tone="error">Pretraga nije uspjela. {errorMessage(search.error)}</Alert>
        </div>
      )}
      {q.trim().length >= 2 && search.isSuccess && (
        <ul className="mt-3 flex flex-col gap-2">
          {results.length === 0 && <li className="text-sm text-slate-600 dark:text-slate-400">Nema aktivnih članova izvan prozivke.</li>}
          {results.map((m) => {
            const name = `${m.firstName} ${m.lastName}`;
            return (
              <li key={m.membershipId} className="flex items-center justify-between gap-2">
                <span className="min-w-0 break-words">{name}</span>
                <Button variant="secondary" loading={busy === m.membershipId} disabled={busy !== undefined} onClick={() => add(m.membershipId, name)}>
                  Prisutan
                </Button>
              </li>
            );
          })}
        </ul>
      )}
    </section>
  );
}

/** "Označi sve preostale odsutnima" (spec §6.3): confirmation with the candidates, then the real changed count. */
function BulkAbsent({ trainingId, roster, started }: { trainingId: number; roster: Roster; started: boolean }) {
  const [confirming, setConfirming] = useState(false);
  const [result, setResult] = useState<string>();
  const bulk = useMarkUnconfirmedAbsent(trainingId);
  const candidates: RosterEntry[] = roster.entries.filter(
    (e) => e.registrationStatus === 'REGISTERED' && e.attendanceStatus === 'UNMARKED',
  );

  const confirm = () => {
    setResult(undefined);
    bulk.mutateAsync(candidates.map((c) => c.membershipId)).then(
      (r) => {
        setConfirming(false);
        setResult(
          r.updatedCount === candidates.length
            ? `Označeno odsutnima: ${r.updatedCount}.`
            : `Označeno odsutnima: ${r.updatedCount} od ${candidates.length} – ostali su u međuvremenu evidentirani ili su otkazali prijavu.`,
        );
      },
      () => setConfirming(false),
    );
  };

  return (
    <section aria-labelledby="bulk-absent" className="rounded-2xl border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900">
      <h2 id="bulk-absent" className="mb-2 font-semibold">
        Preostali prijavljeni
      </h2>
      {result && (
        <div className="mb-3">
          <Alert tone="success">{result}</Alert>
        </div>
      )}
      {bulk.isError && (
        <div className="mb-3">
          <Alert tone="error" title="Nitko nije označen">{errorMessage(bulk.error)}</Alert>
        </div>
      )}
      {!started && <p className="mb-2 text-sm text-slate-600 dark:text-slate-400">Masovno označavanje moguće je nakon početka treninga.</p>}
      {!confirming ? (
        <Button
          variant="danger"
          fullWidth
          disabled={!started || candidates.length === 0}
          onClick={() => {
            bulk.reset();
            setResult(undefined);
            setConfirming(true);
          }}
        >
          Označi sve preostale odsutnima ({candidates.length})
        </Button>
      ) : (
        <div className="flex flex-col gap-3 rounded-xl border border-red-300 bg-red-50/60 p-3 dark:border-red-900 dark:bg-red-950/30">
          <p className="font-semibold">
            Označiti odsutnima {candidates.length} {candidates.length === 1 ? 'člana' : 'članova'}?
          </p>
          <ul className="list-disc pl-5 text-sm">
            {candidates.map((c) => (
              <li key={c.membershipId}>
                {c.firstName} {c.lastName}
              </li>
            ))}
          </ul>
          <p className="text-sm">Već označeni i oni koji su otkazali prijavu se ne mijenjaju. Svaki član dobiva obavijest.</p>
          <div className="flex flex-wrap gap-2">
            <Button variant="danger" loading={bulk.isPending} onClick={confirm}>
              Potvrdi ({candidates.length})
            </Button>
            <Button variant="ghost" disabled={bulk.isPending} onClick={() => setConfirming(false)}>
              Odustani
            </Button>
          </div>
        </div>
      )}
    </section>
  );
}
