import { useState } from 'react';
import { Alert } from '../../../components/ui/Alert';
import { Button } from '../../../components/ui/Button';
import { ApiError } from '../../../lib/api/client';
import { errorMessage } from '../../../lib/api/errors';
import { hasAnyRole } from '../../../lib/session/roles';
import { useCurrentUser } from '../../../lib/session/session';
import { useMyRegistration, type TrainingSummary } from '../api';

const ATTENDANCE_LABELS: Record<TrainingSummary['myAttendanceStatus'], string> = {
  PRESENT: 'Dolazak evidentiran: prisutni',
  ABSENT: 'Dolazak evidentiran: odsutni',
  UNMARKED: 'Dolazak još nije evidentiran',
};

const REGISTRATION_LABELS: Record<TrainingSummary['myRegistrationStatus'], string> = {
  REGISTERED: 'Prijavljeni ste',
  CANCELLED: 'Prijava otkazana',
  NONE: 'Niste prijavljeni',
};

/**
 * Register / cancel the own registration (spec §5.1, §12.4) - one tap from the home screen. The registration
 * ("prijava") and the recorded attendance ("dolazak") are shown separately: a registration is not a visit. Once
 * attendance is recorded no own change is offered. Success is shown only after the server confirmed it.
 */
export function RegistrationControl({ training, size = 'large' }: { training: TrainingSummary; size?: 'large' | 'small' }) {
  const { data: user } = useCurrentUser();
  const { register, cancel } = useMyRegistration(training.trainingId);
  const [message, setMessage] = useState<{ tone: 'success' | 'error' | 'warning'; text: string }>();

  if (!hasAnyRole(user, ['MEMBER'])) return null;

  const busy = register.isPending || cancel.isPending;
  const recorded = training.myAttendanceStatus !== 'UNMARKED';

  const run = (action: typeof register, success: (r: { changed: boolean }) => string) => {
    setMessage(undefined);
    action.mutateAsync().then(
      (result) => setMessage({ tone: 'success', text: success(result) }),
      (error: unknown) =>
        setMessage(
          error instanceof ApiError && error.status === 409
            ? { tone: 'warning', text: `${errorMessage(error)} Prikaz je osvježen.` }
            : { tone: 'error', text: `Promjena nije spremljena. ${errorMessage(error)}` },
        ),
    );
  };

  return (
    <div className="flex flex-col gap-2">
      <p className="flex flex-wrap gap-x-3 gap-y-1 text-sm">
        <span className={`font-semibold ${training.myRegistrationStatus === 'REGISTERED' ? 'text-green-800 dark:text-green-300' : ''}`}>
          {REGISTRATION_LABELS[training.myRegistrationStatus]}
        </span>
        {(training.myRegistrationStatus !== 'NONE' || recorded) && (
          <span className="text-slate-600 dark:text-slate-400">{ATTENDANCE_LABELS[training.myAttendanceStatus]}</span>
        )}
      </p>
      {training.canRegister && (
        <Button
          fullWidth={size === 'large'}
          loading={register.isPending}
          disabled={busy}
          onClick={() => run(register, (r) => (r.changed ? 'Prijavljeni ste. Potvrda je u obavijestima.' : 'Već ste prijavljeni.'))}
        >
          Prijavi se
        </Button>
      )}
      {training.canCancelRegistration && (
        <Button
          variant="secondary"
          fullWidth={size === 'large'}
          loading={cancel.isPending}
          disabled={busy}
          onClick={() => run(cancel, (r) => (r.changed ? 'Prijava je otkazana.' : 'Prijava je već otkazana.'))}
        >
          Otkaži prijavu
        </Button>
      )}
      {recorded && (
        <p className="text-sm text-slate-600 dark:text-slate-400">Dolazak je evidentiran; promjene radi trener.</p>
      )}
      {message && <Alert tone={message.tone}>{message.text}</Alert>}
    </div>
  );
}
