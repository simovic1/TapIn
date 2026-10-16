import { useState, type FormEvent } from 'react';
import { Link } from 'react-router';
import { Alert } from '../../../components/ui/Alert';
import { Button } from '../../../components/ui/Button';
import { TextArea } from '../../../components/ui/TextArea';
import { ApiError } from '../../../lib/api/client';
import { errorMessage } from '../../../lib/api/errors';
import { formatLongDate, formatTimeRange } from '../../../lib/format';
import { useCancelTraining, type TrainingDetail } from '../api';
import { trainingTitle } from './TrainingBits';

/**
 * Trainer actions on one session (spec §12.2). Cancelling first shows exactly what is cancelled and what is not
 * (the weekly rule, registrations and history stay); only a confirmed server response is reported as success.
 */
export function StaffTrainingActions({ training, onReload }: { training: TrainingDetail; onReload: () => void }) {
  const [confirming, setConfirming] = useState(false);
  const [reason, setReason] = useState('');
  const [result, setResult] = useState<string>();
  const cancel = useCancelTraining(training.trainingId);
  // Decision D-03: a session that has started or was held cannot be moved or cancelled.
  const started = training.status === 'HELD' || Date.parse(training.startAt) <= Date.now();
  const cancellable = training.status === 'SCHEDULED' && !started;

  const submit = (event: FormEvent) => {
    event.preventDefault();
    cancel.mutate(
      { expectedVersion: training.version, reason: reason.trim() || null },
      {
        onSuccess: (r) => {
          setConfirming(false);
          setResult(
            r.notificationStatus === 'QUEUED'
              ? 'Trening je otkazan. Obavijest prijavljenima je stavljena u red za slanje.'
              : 'Trening je otkazan.',
          );
        },
      },
    );
  };

  const conflict = cancel.error instanceof ApiError && cancel.error.problem.errorCode === 'VERSION_CONFLICT';

  return (
    <section aria-labelledby="training-actions" className="mt-6 flex flex-col gap-3">
      <h2 id="training-actions" className="text-lg font-semibold">
        Upravljanje terminom
      </h2>
      {result && <Alert tone="success">{result}</Alert>}
      {training.status !== 'CANCELLED' && (
        <div className="flex flex-wrap gap-2">
          <Link
            to={`/prozivka/${training.trainingId}`}
            className="inline-flex min-h-11 items-center rounded-lg bg-slate-900 px-4 text-sm font-semibold text-white hover:bg-slate-700 dark:bg-white dark:text-slate-900"
          >
            Prozivka
          </Link>
          <Link
            to={`/treninzi/${training.trainingId}/uredi`}
            className="inline-flex min-h-11 items-center rounded-lg border border-slate-300 px-4 text-sm font-semibold hover:bg-slate-100 dark:border-slate-700 dark:hover:bg-slate-800"
          >
            {started ? 'Uredi naziv, napomenu i trenere' : 'Pomakni ili uredi ovaj termin'}
          </Link>
          {cancellable && !confirming && (
            <Button variant="danger" onClick={() => { cancel.reset(); setConfirming(true); }}>
              Otkaži trening
            </Button>
          )}
        </div>
      )}
      {started && training.status !== 'CANCELLED' && (
        <p className="text-sm text-slate-600 dark:text-slate-400">
          Trening je započeo ili je održan: više se ne može pomaknuti ni otkazati. Evidencija dolazaka ostaje otvorena za ispravke.
        </p>
      )}
      {confirming && (
        <form onSubmit={submit} className="flex flex-col gap-3 rounded-2xl border border-red-300 bg-red-50/60 p-4 dark:border-red-900 dark:bg-red-950/30">
          <h3 className="font-semibold">Otkazujete samo ovaj termin</h3>
          <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-sm">
            <dt className="text-slate-600 dark:text-slate-400">Trening</dt>
            <dd>{trainingTitle(training)}</dd>
            <dt className="text-slate-600 dark:text-slate-400">Datum</dt>
            <dd className="first-letter:uppercase">{formatLongDate(training.startAt)}</dd>
            <dt className="text-slate-600 dark:text-slate-400">Vrijeme</dt>
            <dd>{formatTimeRange(training.startAt, training.endAt)}</dd>
            {training.registeredCount !== undefined && (
              <>
                <dt className="text-slate-600 dark:text-slate-400">Prijavljeno</dt>
                <dd>{training.registeredCount}</dd>
              </>
            )}
          </dl>
          <ul className="list-disc pl-5 text-sm">
            {training.scheduleId && <li>Tjedni raspored ostaje – ostali termini održavaju se normalno.</li>}
            <li>Prijavljeni članovi dobit će obavijest e-mailom.</li>
            <li>Prijave i povijest termina se ne brišu; otkazani trening ne ulazi u statistiku.</li>
          </ul>
          <TextArea label="Razlog (neobvezno)" value={reason} maxLength={500} onChange={(e) => setReason(e.target.value)} hint="Članovi će vidjeti razlog uz otkazani termin." />
          {cancel.isError && (
            <Alert tone="error" title="Trening nije otkazan">
              {conflict ? 'Termin je u međuvremenu izmijenjen. Učitajte najnovije podatke i provjerite ga ponovno.' : errorMessage(cancel.error)}
              {conflict && (
                <Button variant="secondary" className="mt-2" onClick={() => { setConfirming(false); onReload(); }}>
                  Učitaj najnovije
                </Button>
              )}
            </Alert>
          )}
          <div className="flex flex-wrap gap-2">
            <Button type="submit" variant="danger" loading={cancel.isPending}>
              Potvrdi otkazivanje
            </Button>
            <Button variant="ghost" onClick={() => setConfirming(false)} disabled={cancel.isPending}>
              Odustani
            </Button>
          </div>
        </form>
      )}
    </section>
  );
}
