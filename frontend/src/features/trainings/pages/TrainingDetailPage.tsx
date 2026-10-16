import { Link, useParams } from 'react-router';
import { Alert } from '../../../components/ui/Alert';
import { PageHeader } from '../../../components/ui/PageHeader';
import { QueryState } from '../../../components/ui/QueryState';
import { formatLocalDate, formatLongDate, formatTimeRange } from '../../../lib/format';
import { hasAnyRole, STAFF_ROLES } from '../../../lib/session/roles';
import { useCurrentUser } from '../../../lib/session/session';
import { useTraining } from '../api';
import { coachNames, registrationLabel, TrainingStatusBadge, trainingTitle } from '../components/TrainingBits';

export function TrainingDetailPage() {
  const { trainingId } = useParams();
  const id = Number(trainingId);
  const training = useTraining(id);
  const { data: user } = useCurrentUser();
  const isStaff = hasAnyRole(user, STAFF_ROLES);

  return (
    <section>
      <p className="mb-2 text-sm">
        <Link to="/treninzi" className="underline underline-offset-2">
          ← Svi treninzi
        </Link>
      </p>
      <QueryState query={training} loadingLabel="Učitavanje treninga…">
        {(t) => (
          <>
            <PageHeader title={trainingTitle(t)} description={`${formatLongDate(t.startAt)}, ${formatTimeRange(t.startAt, t.endAt)}`} />
            {t.status === 'CANCELLED' && (
              <div className="mb-4">
                <Alert tone="error" title="Trening je otkazan">
                  {t.cancellationReason ?? 'Razlog nije naveden.'}
                </Alert>
              </div>
            )}
            <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-2 rounded-2xl border border-slate-200 bg-white p-4 text-sm dark:border-slate-800 dark:bg-slate-900">
              <dt className="text-slate-500 dark:text-slate-400">Status</dt>
              <dd>
                <TrainingStatusBadge status={t.status} />
              </dd>
              <dt className="text-slate-500 dark:text-slate-400">Datum</dt>
              <dd className="first-letter:uppercase">{formatLongDate(t.startAt)}</dd>
              <dt className="text-slate-500 dark:text-slate-400">Vrijeme</dt>
              <dd className={`tabular-nums ${t.status === 'CANCELLED' ? 'line-through' : ''}`}>
                {formatTimeRange(t.startAt, t.endAt)}
              </dd>
              {t.occurrenceDate && t.occurrenceDate !== t.localDate && (
                <>
                  <dt className="text-slate-500 dark:text-slate-400">Pomaknut s</dt>
                  <dd className="first-letter:uppercase">{formatLocalDate(t.occurrenceDate)}</dd>
                </>
              )}
              <dt className="text-slate-500 dark:text-slate-400">Treneri</dt>
              <dd>{t.coaches.length > 0 ? coachNames(t.coaches) : 'Nisu dodijeljeni'}</dd>
              {t.note && (
                <>
                  <dt className="text-slate-500 dark:text-slate-400">Napomena</dt>
                  <dd className="whitespace-pre-line">{t.note}</dd>
                </>
              )}
              {hasAnyRole(user, ['MEMBER']) && (
                <>
                  <dt className="text-slate-500 dark:text-slate-400">Vaša prijava</dt>
                  <dd>{registrationLabel(t)}</dd>
                </>
              )}
              {isStaff && t.registeredCount !== undefined && (
                <>
                  <dt className="text-slate-500 dark:text-slate-400">Prijavljeno</dt>
                  <dd>{t.registeredCount}</dd>
                </>
              )}
              <dt className="text-slate-500 dark:text-slate-400">Vrsta</dt>
              <dd>{t.scheduleId ? 'Iz tjednog rasporeda' : 'Izvanredni trening'}</dd>
            </dl>
          </>
        )}
      </QueryState>
    </section>
  );
}
