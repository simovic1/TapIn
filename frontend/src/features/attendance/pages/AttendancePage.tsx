import { Link } from 'react-router';
import { PageHeader } from '../../../components/ui/PageHeader';
import { QueryState } from '../../../components/ui/QueryState';
import { formatLongDate, formatTimeRange } from '../../../lib/format';
import { useRollCallSessions, type TrainingSummary } from '../api';

/** The session a trainer most likely wants now: in progress, else the next one today or later, else the last one. */
function suggested(items: TrainingSummary[], now = Date.now()): TrainingSummary | undefined {
  const inProgress = items.find((t) => Date.parse(t.startAt) <= now && Date.parse(t.endAt) > now);
  if (inProgress) return inProgress;
  const next = items.find((t) => Date.parse(t.startAt) > now);
  const last = [...items].reverse().find((t) => Date.parse(t.endAt) <= now);
  // Right after a session the roll call of that session is still the relevant one (corrections, late marking).
  if (last && now - Date.parse(last.endAt) < 6 * 3_600_000) return last;
  return next ?? last;
}

/** Entry to the roll call (spec §12.2 "Danas / sljedeći trening"): pick the session, the roster opens. */
export function AttendancePage() {
  const sessions = useRollCallSessions();
  return (
    <section className="max-w-3xl">
      <PageHeader title="Prozivka" description="Odaberite trening za evidenciju dolazaka." />
      <p className="mb-4"><Link className="underline" to="/treninzi/novi">Dodaj trening (uključujući povijesni)</Link> · <Link className="underline" to="/treninzi">Kalendar i stara evidencija</Link></p>
      <QueryState
        query={sessions}
        loadingLabel="Učitavanje treninga…"
        isEmpty={(items) => items.length === 0}
        empty={{ title: 'Nema treninga u zadnjih i sljedećih 7 dana', description: 'Stariji trening otvorite iz kalendara.' }}
      >
        {(items) => {
          const top = suggested(items);
          return (
            <div className="flex flex-col gap-4">
              {top && (
                <Link
                  to={`/prozivka/${top.trainingId}`}
                  className="flex min-h-11 flex-col gap-1 rounded-2xl bg-slate-900 p-4 text-white hover:bg-slate-700 dark:bg-white dark:text-slate-900"
                >
                  <span className="text-sm font-semibold uppercase opacity-80">Otvori prozivku</span>
                  <span className="text-xl font-semibold tabular-nums">{formatTimeRange(top.startAt, top.endAt)}</span>
                  <span className="first-letter:uppercase">
                    {formatLongDate(top.startAt)} · {top.title ?? 'Trening'}
                    {top.registeredCount !== undefined && ` · prijavljeno: ${top.registeredCount}`}
                  </span>
                </Link>
              )}
              <ul className="flex flex-col gap-2" aria-label="Ostali treninzi">
                {items
                  .filter((t) => t.trainingId !== top?.trainingId)
                  .map((t) => (
                    <li key={t.trainingId}>
                      <Link
                        to={`/prozivka/${t.trainingId}`}
                        className="flex min-h-11 items-center justify-between gap-2 rounded-xl border border-slate-200 bg-white px-4 py-2 hover:bg-slate-50 dark:border-slate-800 dark:bg-slate-900"
                      >
                        <span className="first-letter:uppercase">
                          {formatLongDate(t.startAt)}, {formatTimeRange(t.startAt, t.endAt)}
                        </span>
                        {t.registeredCount !== undefined && <span className="text-sm text-slate-600">{t.registeredCount}</span>}
                      </Link>
                    </li>
                  ))}
              </ul>
            </div>
          );
        }}
      </QueryState>
    </section>
  );
}
