import { useState } from 'react';
import { Button } from '../../../components/ui/Button';
import { PageHeader } from '../../../components/ui/PageHeader';
import { QueryState } from '../../../components/ui/QueryState';
import { formatDateTime, formatLocalDate, formatTime } from '../../../lib/format';
import { BELT_LABELS } from '../../belts/api';
import { awardedLabel, BeltSwatch, shortDate } from '../../belts/components/BeltHistoryView';
import {
  HISTORY_PAGE_SIZE,
  percent,
  useOwnHistory,
  useOwnStatistics,
  type AttendanceHistoryEntry,
  type AttendanceRate,
  type MemberStatistics,
} from '../api';

/**
 * "Moja statistika" (spec §9.1, §12.1, BJJ-32). Every number comes from the server (BJJ-31, decision D-05); the page
 * never counts anything itself. Registrations and trainings are shown apart, every period names its dates, every
 * percentage names its basis, and UNMARKED ("waiting for the trainer") never looks like a confirmed absence.
 */
export function StatisticsPage() {
  const stats = useOwnStatistics();
  return (
    <>
      <PageHeader
        title="Moja statistika"
        description="Broje se samo potvrđeni dolasci na održane treninge. Otkazani treninzi se ne broje."
        actions={
          <Button variant="secondary" onClick={() => void stats.refetch()} loading={stats.isFetching && !stats.isPending}>
            Osvježi
          </Button>
        }
      />
      <QueryState query={stats} loadingLabel="Učitavanje statistike…">
        {(data) => <Statistics stats={data} />}
      </QueryState>
    </>
  );
}

const card = 'rounded-2xl border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900';
const muted = 'text-slate-600 dark:text-slate-400';

function Statistics({ stats }: { stats: MemberStatistics }) {
  const w = stats.windows;
  return (
    <div className="flex max-w-3xl flex-col gap-8">
      <section aria-labelledby="trainings-heading" className="flex flex-col gap-3">
        <h2 id="trainings-heading" className="text-lg font-semibold">Odrađeni treninzi</h2>
        <dl className="grid grid-cols-2 gap-3 sm:grid-cols-4">
          <Counter label="Ukupno" value={stats.totalPresent} period={`od učlanjenja ${shortDate(stats.memberSince)}`} />
          <Counter label="Ovaj mjesec" value={stats.presentThisMonth} period={range(w.thisMonthFrom, w.today)} />
          <Counter label="Zadnjih 7 dana" value={stats.presentLast7Days} period={range(w.last7DaysFrom, w.today)} />
          <Counter label="Zadnjih 30 dana" value={stats.presentLast30Days} period={range(w.last30DaysFrom, w.today)} />
        </dl>
        <p className={`text-xs ${muted}`}>
          Razdoblja uključuju današnji dan i računaju se po datumu treninga. Stanje: {formatDateTime(stats.asOf)}.
        </p>
      </section>

      <section aria-labelledby="registrations-heading" className="flex flex-col gap-3">
        <h2 id="registrations-heading" className="text-lg font-semibold">Prijave na održane treninge</h2>
        <p className={`text-sm ${muted}`}>
          Prijava nije dolazak: trening se broji tek kad ga trener potvrdi. Dolasci bez prijave ubrajaju se u treninge, ali ne
          u prijave.
        </p>
        <dl className="grid grid-cols-2 gap-3 sm:grid-cols-4">
          <Counter label="Prijave" value={stats.registrations.total} />
          <Counter label="Došli ste" value={stats.registrations.present} />
          <Counter label="Prijavljeni, niste došli" value={stats.registeredButAbsent} />
          <Counter
            label="Čeka evidenciju"
            value={stats.registrations.unmarked}
            period="trener još nije označio – nije odsutnost"
          />
        </dl>
      </section>

      <section aria-labelledby="rate-heading" className="flex flex-col gap-3">
        <h2 id="rate-heading" className="text-lg font-semibold">Postotak dolazaka</h2>
        <div className="grid gap-3 sm:grid-cols-2">
          <Rate
            title="Dolasci na održane treninge"
            rate={stats.attendanceRate}
            basis={(r) =>
              `${r.numerator} od ${r.denominator} održanih treninga od učlanjenja (${shortDate(stats.memberSince)}), dok je članstvo bilo aktivno.`
            }
            empty="Još nema održanih treninga od vašeg učlanjenja."
          />
          <Rate
            title="Dolazak po prijavi"
            rate={stats.registrationAttendanceRate}
            basis={(r) =>
              `${r.numerator} od ${r.denominator} prijava za koje je trener evidentirao dolazak ili izostanak.`
            }
            empty="Još nema prijava s evidentiranim dolaskom ili izostankom."
            note={
              stats.registrations.unmarked > 0
                ? `${stats.registrations.unmarked} ${stats.registrations.unmarked === 1 ? 'prijava čeka evidenciju i nije uračunata' : 'prijava čeka evidenciju i nisu uračunate'}.`
                : undefined
            }
          />
        </div>
      </section>

      <BeltSection stats={stats} />
      <History />
    </div>
  );
}

function range(from: string, to: string) {
  return `${shortDate(from)} – ${shortDate(to)}`;
}

function Counter({ label, value, period }: { label: string; value: number; period?: string }) {
  return (
    <div className={card}>
      <dt className={`text-sm ${muted}`}>{label}</dt>
      <dd className="text-2xl font-semibold tabular-nums">{value}</dd>
      {period && <dd className={`mt-1 text-xs ${muted}`}>{period}</dd>}
    </div>
  );
}

function Rate({
  title,
  rate,
  basis,
  empty,
  note,
}: {
  title: string;
  rate: AttendanceRate;
  basis: (rate: AttendanceRate) => string;
  empty: string;
  note?: string;
}) {
  const value = percent(rate);
  return (
    <div className={card}>
      <h3 className={`text-sm ${muted}`}>{title}</h3>
      {value === null ? (
        <p className="mt-1">{empty}</p>
      ) : (
        <>
          <p className="text-2xl font-semibold tabular-nums">{value}</p>
          <p className="mt-1 text-sm">{basis(rate)}</p>
        </>
      )}
      {note && <p className={`mt-1 text-xs ${muted}`}>{note}</p>}
    </div>
  );
}

function BeltSection({ stats }: { stats: MemberStatistics }) {
  const unknown = stats.currentBelt === 'UNKNOWN';
  return (
    <section aria-labelledby="belt-heading" className="flex flex-col gap-3">
      <h2 id="belt-heading" className="text-lg font-semibold">Treninzi po pojasu</h2>
      {unknown ? (
        <p className={`text-sm ${muted}`}>
          Trener još nije unio vaš pojas. Svi treninzi ({stats.unassignedTrainings}) zasad su neraspoređeni.
        </p>
      ) : (
        <table className="w-full table-fixed border-separate border-spacing-0 overflow-hidden rounded-xl border border-slate-200 text-sm dark:border-slate-800">
          <thead className="bg-slate-50 text-left dark:bg-slate-800/60">
            <tr>
              <th scope="col" className="p-2">Pojas i razdoblje</th>
              <th scope="col" className="w-[34%] p-2">Dodijeljen</th>
              <th scope="col" className="w-[22%] p-2 text-right">Treninzi</th>
            </tr>
          </thead>
          <tbody>
            {[...stats.beltPeriods].reverse().map((p) => (
              <tr key={p.beltPeriodId} className="align-top">
                <td className="border-t border-slate-200 p-2 dark:border-slate-800">
                  <span className="flex items-center gap-2 font-medium">
                    <BeltSwatch belt={p.belt} />
                    {BELT_LABELS[p.belt]}
                    {p.effectiveToDate === null && <span className={`text-xs font-normal ${muted}`}>(trenutačni)</span>}
                  </span>
                  <span className={`block text-xs ${muted}`}>
                    od {shortDate(p.effectiveFromDate)}
                    {p.effectiveToDate ? ` do ${shortDate(p.effectiveToDate)}` : ' – danas'}
                  </span>
                </td>
                <td className="border-t border-slate-200 p-2 dark:border-slate-800">{awardedLabel(p.awardedOn)}</td>
                <td className="border-t border-slate-200 p-2 text-right font-semibold tabular-nums dark:border-slate-800">
                  {p.trainingsCount}
                </td>
              </tr>
            ))}
            {stats.unassignedTrainings > 0 && (
              <tr>
                <td colSpan={2} className="border-t border-slate-200 p-2 dark:border-slate-800">
                  Neraspoređeni dolasci
                  <span className={`block text-xs ${muted}`}>prije prvog poznatog pojasa</span>
                </td>
                <td className="border-t border-slate-200 p-2 text-right font-semibold tabular-nums dark:border-slate-800">
                  {stats.unassignedTrainings}
                </td>
              </tr>
            )}
          </tbody>
        </table>
      )}
    </section>
  );
}

function History() {
  const [page, setPage] = useState(0);
  const history = useOwnHistory(page);
  return (
    <section aria-labelledby="history-heading" className="flex flex-col gap-3">
      <h2 id="history-heading" className="text-lg font-semibold">Povijest treninga</h2>
      <QueryState
        query={history}
        loadingLabel="Učitavanje povijesti…"
        isEmpty={(data) => data.items.length === 0 && page === 0}
        empty={{ title: 'Još nema treninga', description: 'Ovdje će se pojaviti treninzi na koje se prijavite ili na kojima vas trener evidentira.' }}
      >
        {(data) => (
          <>
            <ul aria-labelledby="history-heading" className="flex flex-col divide-y divide-slate-200 rounded-2xl border border-slate-200 bg-white dark:divide-slate-800 dark:border-slate-800 dark:bg-slate-900">
              {data.items.map((entry) => (
                <HistoryRow key={entry.trainingId} entry={entry} />
              ))}
            </ul>
            {data.page.totalPages > 1 && (
              <nav aria-label="Stranice povijesti" className="flex items-center justify-between gap-2">
                <Button variant="secondary" disabled={page === 0 || history.isFetching} onClick={() => setPage(page - 1)}>
                  Novije
                </Button>
                <span className={`text-sm ${muted}`}>
                  {page + 1} / {data.page.totalPages} ({data.page.totalElements} treninga, po {HISTORY_PAGE_SIZE})
                </span>
                <Button
                  variant="secondary"
                  disabled={page + 1 >= data.page.totalPages || history.isFetching}
                  onClick={() => setPage(page + 1)}
                >
                  Starije
                </Button>
              </nav>
            )}
          </>
        )}
      </QueryState>
    </section>
  );
}

const REGISTRATION_LABELS: Record<AttendanceHistoryEntry['registrationStatus'], string> = {
  REGISTERED: 'Prijavljeni',
  CANCELLED: 'Prijava otkazana',
  NONE: 'Bez prijave',
};

const BADGE = {
  present: 'bg-green-100 text-green-900 dark:bg-green-950 dark:text-green-100',
  absent: 'bg-red-100 text-red-900 dark:bg-red-950 dark:text-red-100',
  pending: 'border border-dashed border-slate-400 text-slate-700 dark:text-slate-300',
  cancelled: 'bg-slate-100 text-slate-700 dark:bg-slate-800 dark:text-slate-300',
};

/** UNMARKED is "waiting for the trainer" (or nothing, for a cancelled training) - never shown as an absence. */
function outcome(entry: AttendanceHistoryEntry): { label: string; tone: keyof typeof BADGE } {
  if (entry.trainingStatus === 'CANCELLED') return { label: 'Trening otkazan – ne broji se', tone: 'cancelled' };
  if (entry.attendanceStatus === 'PRESENT') return { label: 'Došli ste – broji se', tone: 'present' };
  if (entry.attendanceStatus === 'ABSENT') return { label: 'Niste došli', tone: 'absent' };
  if (entry.registrationStatus === 'CANCELLED') return { label: 'Bez dolaska', tone: 'cancelled' };
  return { label: 'Čeka evidenciju trenera', tone: 'pending' };
}

function HistoryRow({ entry }: { entry: AttendanceHistoryEntry }) {
  const result = outcome(entry);
  return (
    <li className="flex flex-wrap items-center justify-between gap-2 p-3">
      <div>
        <p className="font-medium">
          {formatLocalDate(entry.localDate)} · {formatTime(entry.startAt)}
        </p>
        <p className={`text-sm ${muted}`}>
          {entry.title ? `${entry.title} · ` : ''}
          {REGISTRATION_LABELS[entry.registrationStatus]}
        </p>
      </div>
      <span className={`rounded-full px-3 py-1 text-sm ${BADGE[result.tone]}`}>{result.label}</span>
    </li>
  );
}
