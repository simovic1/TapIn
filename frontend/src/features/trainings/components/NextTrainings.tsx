import { Link } from 'react-router';
import { QueryState } from '../../../components/ui/QueryState';
import { hasAnyRole } from '../../../lib/session/roles';
import { useCurrentUser } from '../../../lib/session/session';
import { formatLongDate, formatTimeRange } from '../../../lib/format';
import { useUpcomingTrainings, type TrainingSummary } from '../api';
import { RegistrationControl } from './RegistrationControl';
import { coachNames, TrainingStatusBadge, trainingTitle } from './TrainingBits';

const card = 'rounded-2xl border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900';
const cardTitle = 'mb-2 text-sm font-semibold tracking-wide text-slate-500 uppercase dark:text-slate-400';

/**
 * Home screen (spec §12.1): the next and the second next training, plus cancelled sessions that fall before them
 * so a member is never surprised by an empty hall. Registration (BJJ-18) plugs into the status line.
 */
export function NextTrainings() {
  const upcoming = useUpcomingTrainings();
  return (
    <QueryState
      query={upcoming}
      loadingLabel="Učitavanje rasporeda…"
      isEmpty={(items) => items.length === 0}
      empty={{
        title: 'Nema zakazanih treninga',
        description: 'U sljedećih 60 dana nema termina. Raspored će se pojaviti čim ga trener objavi.',
      }}
    >
      {(items) => <NextTrainingsView items={items} />}
    </QueryState>
  );
}

function NextTrainingsView({ items }: { items: TrainingSummary[] }) {
  const active = items.filter((t) => t.status !== 'CANCELLED');
  const [next, second] = active;
  const horizon = second ?? next;
  const cancelledBefore = items.filter(
    (t) => t.status === 'CANCELLED' && (!horizon || Date.parse(t.startAt) <= Date.parse(horizon.startAt)),
  );

  return (
    <div className="grid gap-4 md:grid-cols-2">
      {next ? (
        <section aria-labelledby="next-training" className={`${card} md:col-span-2`}>
          <h2 id="next-training" className={cardTitle}>
            Sljedeći trening
          </h2>
          <TrainingHighlight training={next} primary />
        </section>
      ) : (
        <section className={`${card} md:col-span-2`}>
          <h2 className={cardTitle}>Sljedeći trening</h2>
          <p>Svi nadolazeći termini su otkazani.</p>
        </section>
      )}
      {second && (
        <section aria-labelledby="second-training" className={card}>
          <h2 id="second-training" className={cardTitle}>
            Nakon toga
          </h2>
          <TrainingHighlight training={second} />
        </section>
      )}
      {cancelledBefore.length > 0 && (
        <section aria-labelledby="cancelled-trainings" className={card}>
          <h2 id="cancelled-trainings" className={cardTitle}>
            Otkazano
          </h2>
          <ul className="flex flex-col gap-2">
            {cancelledBefore.map((t) => (
              <li key={t.trainingId}>
                <Link to={`/treninzi/${t.trainingId}`} className="flex min-h-11 items-center justify-between gap-2 underline-offset-2 hover:underline">
                  <span className="first-letter:uppercase">
                    <span className="line-through">{formatLongDate(t.startAt)}, {formatTimeRange(t.startAt, t.endAt)}</span>
                  </span>
                  <TrainingStatusBadge status={t.status} />
                </Link>
              </li>
            ))}
          </ul>
        </section>
      )}
      <p className="text-sm md:col-span-2">
        <Link to="/treninzi" className="font-semibold underline underline-offset-2">
          Cijeli raspored
        </Link>
      </p>
    </div>
  );
}

function TrainingHighlight({ training, primary = false }: { training: TrainingSummary; primary?: boolean }) {
  const inProgress = Date.parse(training.startAt) <= Date.now();
  const { data: user } = useCurrentUser();
  // Registration is a member action; for staff without MEMBER the details link is the main action.
  const isMember = hasAnyRole(user, ['MEMBER']);
  return (
    <div className="flex flex-col gap-3">
      <div>
        <p className={`${primary ? 'text-2xl' : 'text-lg'} font-semibold tabular-nums`}>
          {formatTimeRange(training.startAt, training.endAt)}
        </p>
        <p className="first-letter:uppercase">{formatLongDate(training.startAt)}</p>
        <p className="text-slate-600 dark:text-slate-400">
          {trainingTitle(training)}
          {training.coaches.length > 0 && ` · ${coachNames(training.coaches)}`}
        </p>
      </div>
      {inProgress && <p className="text-sm font-semibold text-green-700 dark:text-green-400">U tijeku</p>}
      <RegistrationControl training={training} />
      <Link
        to={`/treninzi/${training.trainingId}`}
        className={`inline-flex min-h-11 items-center justify-center rounded-lg px-4 text-sm font-semibold ${
          primary && !isMember
            ? 'bg-slate-900 text-white hover:bg-slate-700 dark:bg-white dark:text-slate-900 dark:hover:bg-slate-200'
            : 'border border-slate-300 hover:bg-slate-100 dark:border-slate-700 dark:hover:bg-slate-800'
        }`}
      >
        Detalji treninga
      </Link>
    </div>
  );
}
