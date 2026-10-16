import { Link, useSearchParams } from 'react-router';
import { Button } from '../../../components/ui/Button';
import { PageHeader } from '../../../components/ui/PageHeader';
import { QueryState } from '../../../components/ui/QueryState';
import { clubDateTimeToIso, clubToday, dateParts, formatLocalDate } from '../../../lib/format';
import { hasAnyRole, STAFF_ROLES } from '../../../lib/session/roles';
import { useCurrentUser } from '../../../lib/session/session';
import { useTrainings, type TrainingSummary } from '../api';
import { TrainingListItem } from '../components/TrainingBits';

const MONTH = /^(\d{4})-(0[1-9]|1[0-2])$/;

function shiftMonth(month: string, delta: number): string {
  const [y, m] = dateParts(month);
  const date = new Date(Date.UTC(y, m - 1 + delta, 1));
  return date.toISOString().slice(0, 7);
}

const monthLabel = (month: string) =>
  formatLocalDate(`${month}-01`, { weekday: undefined, day: undefined, month: 'long', year: 'numeric' });

/** Groups sessions by their local date in the club zone, keeping start order. */
function byLocalDate(items: TrainingSummary[]): [string, TrainingSummary[]][] {
  const groups = new Map<string, TrainingSummary[]>();
  for (const t of items) {
    const day = t.localDate;
    groups.set(day, [...(groups.get(day) ?? []), t]);
  }
  return [...groups.entries()];
}

/** Calendar of past and future sessions as a month agenda (readable on a phone), spec §12.1. */
export function TrainingsPage() {
  const [params, setParams] = useSearchParams();
  const { data: user } = useCurrentUser();
  const isStaff = hasAnyRole(user, STAFF_ROLES);
  const today = clubToday();
  const currentMonth = today.slice(0, 7);
  const requested = params.get('mjesec');
  const month = requested && MONTH.test(requested) ? requested : currentMonth;
  const range = {
    from: clubDateTimeToIso(`${month}-01`, '00:00')!,
    to: clubDateTimeToIso(`${shiftMonth(month, 1)}-01`, '00:00')!,
  };
  const trainings = useTrainings(range);
  const goTo = (target: string) =>
    setParams(target === currentMonth ? {} : { mjesec: target }, { preventScrollReset: true });

  return (
    <section>
      <PageHeader
        title="Treninzi"
        description="Raspored po zagrebačkom vremenu. Otkazani termini su jasno označeni."
        actions={
          isStaff && (
            <>
              <Link
                to="/treninzi/novi"
                className="inline-flex min-h-11 items-center rounded-lg bg-slate-900 px-4 text-sm font-semibold text-white hover:bg-slate-700 dark:bg-white dark:text-slate-900"
              >
                Dodaj trening
              </Link>
              <Link
                to="/treninzi/raspored"
                className="inline-flex min-h-11 items-center rounded-lg border border-slate-300 px-4 text-sm font-semibold hover:bg-slate-100 dark:border-slate-700 dark:hover:bg-slate-800"
              >
                Tjedni raspored
              </Link>
            </>
          )
        }
      />

      <nav aria-label="Odabir mjeseca" className="mb-4 flex items-center justify-between gap-2">
        <Button variant="secondary" onClick={() => goTo(shiftMonth(month, -1))} aria-label="Prethodni mjesec">
          ‹
        </Button>
        <div className="flex flex-col items-center">
          <h2 className="text-lg font-semibold first-letter:uppercase" aria-live="polite">
            {monthLabel(month)}
          </h2>
          {month !== currentMonth && (
            <button type="button" onClick={() => goTo(currentMonth)} className="min-h-11 text-sm underline underline-offset-2">
              Ovaj mjesec
            </button>
          )}
        </div>
        <Button variant="secondary" onClick={() => goTo(shiftMonth(month, 1))} aria-label="Sljedeći mjesec">
          ›
        </Button>
      </nav>

      <QueryState
        query={trainings}
        loadingLabel="Učitavanje rasporeda…"
        isEmpty={(items) => items.length === 0}
        empty={{ title: 'Nema treninga u ovom mjesecu', description: 'Odaberite drugi mjesec ili provjerite kasnije.' }}
      >
        {(items) => (
          <ol className="flex flex-col gap-5" aria-busy={trainings.isFetching || undefined}>
            {byLocalDate(items).map(([day, list]) => {
              const isToday = day === today;
              const past = day < today;
              return (
                <li key={day} className={past ? 'opacity-80' : ''}>
                  <h3 className="mb-2 flex items-center gap-2 text-sm font-semibold text-slate-600 dark:text-slate-300">
                    <span className="first-letter:uppercase">{formatLocalDate(day)}</span>
                    {isToday && (
                      <span className="rounded-full bg-green-100 px-2 py-0.5 text-xs text-green-900 dark:bg-green-950 dark:text-green-100">
                        Danas
                      </span>
                    )}
                  </h3>
                  <ul className="flex flex-col gap-2">
                    {list.map((t) => (
                      <li key={t.trainingId}>
                        <TrainingListItem training={t} showDate={false} />
                      </li>
                    ))}
                  </ul>
                </li>
              );
            })}
          </ol>
        )}
      </QueryState>
    </section>
  );
}
