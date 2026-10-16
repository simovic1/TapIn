import { Link } from 'react-router';
import { formatLongDate, formatTimeRange } from '../../../lib/format';
import type { CoachRef, TrainingStatus, TrainingSummary } from '../api';

const STATUS: Record<TrainingStatus, { label: string; className: string }> = {
  SCHEDULED: {
    label: 'Planiran',
    className: 'bg-sky-100 text-sky-900 dark:bg-sky-950 dark:text-sky-100',
  },
  HELD: {
    label: 'Održan',
    className: 'bg-slate-200 text-slate-800 dark:bg-slate-800 dark:text-slate-100',
  },
  CANCELLED: {
    label: 'Otkazan',
    className: 'bg-red-100 text-red-900 ring-1 ring-red-300 dark:bg-red-950 dark:text-red-100 dark:ring-red-800',
  },
};

/** Text label, not colour alone, so the status is clear for everyone (cancelled sessions must stand out). */
export function TrainingStatusBadge({ status }: { status: TrainingStatus }) {
  const { label, className } = STATUS[status];
  return (
    <span className={`inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-semibold ${className}`}>
      {label}
    </span>
  );
}

export const trainingTitle = (t: Pick<TrainingSummary, 'title'>) => t.title ?? 'Trening';

export const coachNames = (coaches: readonly CoachRef[]) =>
  coaches.map((c) => `${c.firstName} ${c.lastName}`).join(', ');

const REGISTRATION_LABELS: Record<TrainingSummary['myRegistrationStatus'], string> = {
  REGISTERED: 'Prijavljeni ste',
  CANCELLED: 'Prijava otkazana',
  NONE: 'Niste prijavljeni',
};

export const registrationLabel = (t: Pick<TrainingSummary, 'myRegistrationStatus'>) =>
  REGISTRATION_LABELS[t.myRegistrationStatus];

/** One session in a list: date, time, title, coaches and status; the whole row opens the detail. */
export function TrainingListItem({ training, showDate = true }: { training: TrainingSummary; showDate?: boolean }) {
  const cancelled = training.status === 'CANCELLED';
  return (
    <Link
      to={`/treninzi/${training.trainingId}`}
      className={`flex min-h-11 items-start justify-between gap-3 rounded-xl border px-4 py-3 transition-colors hover:bg-slate-50 dark:hover:bg-slate-800/60 ${
        cancelled
          ? 'border-red-200 bg-red-50/60 dark:border-red-900 dark:bg-red-950/30'
          : 'border-slate-200 bg-white dark:border-slate-800 dark:bg-slate-900'
      }`}
    >
      <div className="min-w-0">
        <p className={`font-semibold tabular-nums ${cancelled ? 'line-through decoration-2' : ''}`}>
          {formatTimeRange(training.startAt, training.endAt)}
          <span className="ml-2 font-normal">{trainingTitle(training)}</span>
        </p>
        {showDate && <p className="text-sm text-slate-600 first-letter:uppercase dark:text-slate-400">{formatLongDate(training.startAt)}</p>}
        {training.coaches.length > 0 && (
          <p className="truncate text-sm text-slate-600 dark:text-slate-400">Treneri: {coachNames(training.coaches)}</p>
        )}
      </div>
      <div className="flex shrink-0 flex-col items-end gap-1">
        <TrainingStatusBadge status={training.status} />
        {training.registeredCount !== undefined && !cancelled && (
          <span className="text-xs text-slate-600 dark:text-slate-400">Prijavljeno: {training.registeredCount}</span>
        )}
      </div>
    </Link>
  );
}
