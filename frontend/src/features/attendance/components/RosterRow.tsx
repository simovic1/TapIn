import { useState } from 'react';
import { Spinner } from '../../../components/ui/Spinner';
import { ApiError } from '../../../lib/api/client';
import { errorMessage } from '../../../lib/api/errors';
import {
  resetAttendance,
  setAttendance,
  type AttendanceResult,
  type RosterEntry,
} from '../api';

type Target = 'PRESENT' | 'ABSENT' | 'UNMARKED';

type SaveState =
  | { kind: 'idle' }
  | { kind: 'saving'; target: Target }
  | { kind: 'saved' }
  | { kind: 'error'; target: Target; message: string }
  | { kind: 'conflict'; message: string };

export const ATTENDANCE_LABELS: Record<RosterEntry['attendanceStatus'], string> = {
  PRESENT: 'Prisutan',
  ABSENT: 'Odsutan',
  UNMARKED: 'Nije označeno',
};

const REGISTRATION_LABELS = {
  REGISTERED: 'Prijavljen',
  CANCELLED: 'Otkazao prijavu',
  NONE: 'Bez prijave',
} as const;

/**
 * One member in the roll call (spec §6.2): two large buttons, the confirmed state clearly marked, and its own save
 * feedback. A change is shown as saved only after the server confirmed it; a network error offers a retry; a 409
 * shows what another trainer recorded (fresh row from the server) so the trainer decides again - never hidden.
 */
export function RosterRow({
  trainingId,
  entry,
  disabled,
  historical = false,
  onConfirmed,
}: {
  trainingId: number;
  entry: RosterEntry;
  disabled: boolean;
  historical?: boolean;
  onConfirmed: (entry: RosterEntry) => void;
}) {
  const [state, setState] = useState<SaveState>({ kind: 'idle' });
  const [note, setNote] = useState('');
  const saving = state.kind === 'saving';
  const name = `${entry.firstName} ${entry.lastName}`;

  const save = (target: Target) => {
    if (saving) return;
    setState({ kind: 'saving', target });
    const request =
      target === 'UNMARKED'
        ? resetAttendance(trainingId, entry.membershipId, entry.attendanceVersion, note)
        : setAttendance(trainingId, entry.membershipId, target, entry.attendanceVersion, note, entry.attendanceStatus !== 'UNMARKED' ? 'CORRECTION' : historical ? 'HISTORICAL_IMPORT' : 'SINGLE');
    request.then(
      (result: AttendanceResult) => {
        onConfirmed({
          ...entry,
          attendanceStatus: result.status,
          attendanceVersion: result.version,
          attendanceUpdatedAt: result.updatedAt,
          attendanceUpdatedByUserId: result.updatedByUserId,
        });
        setState({ kind: 'saved' });
      },
      (error: unknown) => {
        const fresh = error instanceof ApiError ? (error.problem.currentState as RosterEntry | undefined) : undefined;
        if (error instanceof ApiError && error.problem.errorCode === 'VERSION_CONFLICT' && fresh) {
          onConfirmed(fresh);
          setState({
            kind: 'conflict',
            message: `Netko je u međuvremenu promijenio evidenciju: sada „${ATTENDANCE_LABELS[fresh.attendanceStatus]}”. Provjerite i odlučite ponovno.`,
          });
          return;
        }
        setState({ kind: 'error', target, message: errorMessage(error) });
      },
    );
  };

  const current = entry.attendanceStatus;
  const button = (target: 'PRESENT' | 'ABSENT') => {
    const active = current === target;
    const pending = state.kind === 'saving' && state.target === target;
    const colors =
      target === 'PRESENT'
        ? active
          ? 'bg-green-700 text-white border-green-700'
          : 'border-green-700 text-green-800 dark:text-green-300'
        : active
          ? 'bg-red-700 text-white border-red-700'
          : 'border-red-700 text-red-800 dark:text-red-300';
    return (
      <button
        type="button"
        aria-pressed={active}
        aria-label={`${ATTENDANCE_LABELS[target]}: ${name}`}
        disabled={disabled || saving}
        onClick={() => save(target)}
        className={`inline-flex min-h-12 flex-1 items-center justify-center gap-2 rounded-lg border-2 px-3 text-base font-semibold disabled:opacity-60 ${colors}`}
      >
        {pending && <Spinner className="size-4" />}
        {ATTENDANCE_LABELS[target]}
      </button>
    );
  };

  return (
    <li
      className={`flex flex-col gap-2 rounded-xl border px-3 py-2 ${
        current === 'PRESENT'
          ? 'border-green-300 bg-green-50 dark:border-green-900 dark:bg-green-950/30'
          : current === 'ABSENT'
            ? 'border-red-300 bg-red-50 dark:border-red-900 dark:bg-red-950/30'
            : 'border-slate-200 bg-white dark:border-slate-800 dark:bg-slate-900'
      }`}
    >
      <div className="flex flex-wrap items-baseline justify-between gap-x-3 gap-y-1">
        <p className="min-w-0 font-semibold break-words">{name}</p>
        <p className="text-sm text-slate-600 dark:text-slate-400">
          {REGISTRATION_LABELS[entry.registrationStatus ?? 'NONE']} · <span className="font-medium">{ATTENDANCE_LABELS[current]}</span>
        </p>
      </div>
      <label className="text-sm">Napomena za {name}
        <input className="block w-full rounded border p-2" maxLength={500} value={note} disabled={saving} onChange={e => setNote(e.target.value)} />
      </label>
      <div className="flex gap-2">
        {button('PRESENT')}
        {button('ABSENT')}
      </div>
      {(state.kind !== 'idle' || current !== 'UNMARKED') && (
      <div className="flex flex-wrap items-center justify-between gap-2 text-sm" aria-live="polite">
        <span>
          {state.kind === 'saving' && <span className="text-slate-600 dark:text-slate-400">Spremanje…</span>}
          {state.kind === 'saved' && <span className="text-green-800 dark:text-green-300">Spremljeno</span>}
          {state.kind === 'error' && (
            <span role="alert" className="text-red-800 dark:text-red-300">
              Greška pri spremanju: {state.message}{' '}
              <button type="button" className="min-h-11 font-semibold underline" onClick={() => save(state.target)}>
                Pokušaj ponovno
              </button>
            </span>
          )}
          {state.kind === 'conflict' && (
            <span role="alert" className="text-amber-800 dark:text-amber-300">
              {state.message}
            </span>
          )}
        </span>
        {current !== 'UNMARKED' && (
          <button
            type="button"
            disabled={disabled || saving}
            onClick={() => save('UNMARKED')}
            className="min-h-11 text-slate-600 underline underline-offset-2 disabled:opacity-60 dark:text-slate-400"
          >
            Poništi oznaku
          </button>
        )}
      </div>
      )}
    </li>
  );
}
