import { useState, type FormEvent } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import { Alert } from '../../../components/ui/Alert';
import { Button } from '../../../components/ui/Button';
import { PageHeader } from '../../../components/ui/PageHeader';
import { QueryState } from '../../../components/ui/QueryState';
import { TextArea } from '../../../components/ui/TextArea';
import { TextField } from '../../../components/ui/TextField';
import { ApiError } from '../../../lib/api/client';
import { errorMessage, fieldErrors } from '../../../lib/api/errors';
import { clubDateTimeToIso, clubToday, formatLocalDate, toClubParts } from '../../../lib/format';
import {
  useCreateTraining,
  useTraining,
  useUpdateTraining,
  type TrainingDetail,
  type UpdateTrainingRequest,
} from '../api';
import { CoachPicker } from '../components/CoachPicker';

interface FormValues {
  date: string;
  startTime: string;
  endTime: string;
  title: string;
  note: string;
  coachIds: number[];
  historical: boolean;
}

type Errors = Partial<Record<'date' | 'startTime' | 'endTime' | 'title' | 'note', string>>;

export interface SavedNotice {
  notice: string;
}

const OVERLAP = 'Termin se preklapa s drugim aktivnim treningom. Odaberite drugo vrijeme.';

function valuesOf(t: TrainingDetail): FormValues {
  const start = toClubParts(t.startAt);
  return {
    date: start.date,
    startTime: start.time,
    endTime: toClubParts(t.endAt).time,
    title: t.title ?? '',
    note: t.note ?? '',
    coachIds: t.coaches.map((c) => c.membershipId),
    historical: false,
  };
}

/** Client-side checks; the server repeats them (and decides overlaps). */
function validate(v: FormValues, timesEditable: boolean, now = Date.now()): { errors: Errors; startAt?: string; endAt?: string } {
  const errors: Errors = {};
  if (v.title.length > 150) errors.title = 'Naziv može imati najviše 150 znakova.';
  if (v.note.length > 500) errors.note = 'Napomena može imati najviše 500 znakova.';
  if (!timesEditable) return { errors };
  const startAt = clubDateTimeToIso(v.date, v.startTime);
  const endAt = clubDateTimeToIso(v.date, v.endTime);
  if (!/^\d{4}-\d{2}-\d{2}$/.test(v.date) || !clubDateTimeToIso(v.date, '12:00')) errors.date = 'Unesite ispravan datum.';
  else if (!startAt) errors.startTime = 'Unesite vrijeme početka (HH:mm).';
  else if (!endAt) errors.endTime = 'Unesite vrijeme završetka (HH:mm).';
  else if (Date.parse(endAt) <= Date.parse(startAt)) errors.endTime = 'Završetak mora biti nakon početka.';
  else if (v.historical && Date.parse(endAt) > now) errors.endTime = 'Povijesni trening mora biti završen.';
  else if (!v.historical && Date.parse(startAt) <= now)
    errors.startTime = 'Početak mora biti u budućnosti. Za već održani trening označite povijesni unos.';
  return { errors, startAt: startAt ?? undefined, endAt: endAt ?? undefined };
}

/** Maps server problems to fields; returns the message for the form-level alert. */
function serverErrors(error: unknown): { errors: Errors; message: string } {
  const fields = fieldErrors(error);
  const errors: Errors = {};
  if (fields.startAt) errors.startTime = fields.startAt;
  if (fields.endAt) errors.endTime = fields.endAt;
  if (fields.title) errors.title = fields.title;
  if (fields.note) errors.note = fields.note;
  if (error instanceof ApiError && error.problem.errorCode === 'TRAINING_OVERLAP') {
    errors.startTime = OVERLAP;
    return { errors, message: OVERLAP };
  }
  return { errors, message: `Promjena nije spremljena. ${errorMessage(error)}` };
}

function TrainingFields({
  values,
  set,
  errors,
  timesEditable,
  showHistorical,
}: {
  values: FormValues;
  set: (patch: Partial<FormValues>) => void;
  errors: Errors;
  timesEditable: boolean;
  showHistorical: boolean;
}) {
  return (
    <>
      <div className="grid gap-4 sm:grid-cols-3">
        <TextField
          label="Datum"
          type="date"
          required
          disabled={!timesEditable}
          value={values.date}
          onChange={(e) => set({ date: e.target.value })}
          error={errors.date}
        />
        <TextField
          label="Početak"
          type="time"
          required
          disabled={!timesEditable}
          value={values.startTime}
          onChange={(e) => set({ startTime: e.target.value })}
          error={errors.startTime}
          hint="Zagrebačko vrijeme"
        />
        <TextField
          label="Završetak"
          type="time"
          required
          disabled={!timesEditable}
          value={values.endTime}
          onChange={(e) => set({ endTime: e.target.value })}
          error={errors.endTime}
        />
      </div>
      {showHistorical && (
        <label className="flex min-h-11 items-center gap-3">
          <input
            type="checkbox"
            className="size-5"
            checked={values.historical}
            onChange={(e) => set({ historical: e.target.checked })}
          />
          <span>
            Povijesni trening (već održan) – za naknadni unos dolazaka
          </span>
        </label>
      )}
      <TextField
        label="Naziv"
        value={values.title}
        maxLength={150}
        onChange={(e) => set({ title: e.target.value })}
        error={errors.title}
        hint="Neobvezno, npr. „Open mat” ili „Seminar”."
      />
      <TextArea label="Napomena" value={values.note} maxLength={500} onChange={(e) => set({ note: e.target.value })} error={errors.note} />
      <CoachPicker value={values.coachIds} onChange={(coachIds) => set({ coachIds })} />
    </>
  );
}

/** Extra or historical session (spec §12.2). */
export function NewTrainingPage() {
  const navigate = useNavigate();
  const create = useCreateTraining();
  const [values, setValues] = useState<FormValues>({
    date: clubToday(),
    startTime: '18:30',
    endTime: '20:00',
    title: '',
    note: '',
    coachIds: [],
    historical: false,
  });
  const [errors, setErrors] = useState<Errors>({});
  const [formError, setFormError] = useState<string>();
  const set = (patch: Partial<FormValues>) => setValues((v) => ({ ...v, ...patch }));

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const checked = validate(values, true);
    setErrors(checked.errors);
    setFormError(undefined);
    if (Object.keys(checked.errors).length > 0 || !checked.startAt || !checked.endAt) return;
    create
      .mutateAsync({
        startAt: checked.startAt,
        endAt: checked.endAt,
        title: values.title.trim() || null,
        note: values.note.trim() || null,
        coachMembershipIds: values.coachIds,
        historical: values.historical,
      })
      .then(
        (training) =>
          navigate(`/treninzi/${training.trainingId}`, {
            state: { notice: values.historical ? 'Povijesni trening je dodan.' : 'Trening je dodan u raspored.' } satisfies SavedNotice,
          }),
        (error: unknown) => {
          const mapped = serverErrors(error);
          setErrors(mapped.errors);
          setFormError(mapped.message);
        },
      );
  };

  return (
    <section className="max-w-2xl">
      <p className="mb-2 text-sm">
        <Link to="/treninzi" className="underline underline-offset-2">
          ← Svi treninzi
        </Link>
      </p>
      <PageHeader title="Dodatni trening" description="Jednokratni termin izvan tjednog rasporeda. Tjedna pravila se ne mijenjaju." />
      <form noValidate onSubmit={submit} className="flex flex-col gap-4">
        {formError && <Alert tone="error">{formError}</Alert>}
        <TrainingFields values={values} set={set} errors={errors} timesEditable showHistorical />
        <div className="flex flex-wrap gap-2">
          <Button type="submit" loading={create.isPending}>
            Spremi trening
          </Button>
          <Link to="/treninzi" className="inline-flex min-h-11 items-center px-4 text-sm font-semibold underline underline-offset-2">
            Odustani
          </Link>
        </div>
      </form>
    </section>
  );
}

/** Changes ONE session only; the weekly rule and its other sessions stay as they are. */
export function EditTrainingPage() {
  const id = Number(useParams().trainingId);
  const training = useTraining(id);
  return (
    <section className="max-w-2xl">
      <p className="mb-2 text-sm">
        <Link to={`/treninzi/${id}`} className="underline underline-offset-2">
          ← Natrag na trening
        </Link>
      </p>
      <QueryState query={training} loadingLabel="Učitavanje treninga…">
        {(t) => <EditTrainingForm key={t.version} training={t} onReload={() => training.refetch()} />}
      </QueryState>
    </section>
  );
}

function EditTrainingForm({ training, onReload }: { training: TrainingDetail; onReload: () => void }) {
  const navigate = useNavigate();
  const update = useUpdateTraining(training.trainingId);
  const [values, setValues] = useState<FormValues>(() => valuesOf(training));
  const [errors, setErrors] = useState<Errors>({});
  const [formError, setFormError] = useState<string>();
  const [conflict, setConflict] = useState(false);
  const set = (patch: Partial<FormValues>) => setValues((v) => ({ ...v, ...patch }));
  // Decision D-03: once a session has started (or is HELD) its time is fixed.
  const timesEditable = training.status === 'SCHEDULED' && Date.parse(training.startAt) > Date.now();

  if (training.status === 'CANCELLED') {
    return (
      <>
        <PageHeader title="Izmjena termina" />
        <Alert tone="warning" title="Otkazani trening se ne može mijenjati">
          Za zamjenski termin dodajte dodatni trening.
        </Alert>
      </>
    );
  }

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const checked = validate(values, timesEditable);
    setErrors(checked.errors);
    setFormError(undefined);
    setConflict(false);
    if (Object.keys(checked.errors).length > 0) return;
    const request: UpdateTrainingRequest = {
      expectedVersion: training.version,
      title: values.title.trim() || null,
      note: values.note.trim() || null,
      coachMembershipIds: values.coachIds,
    };
    if (timesEditable && checked.startAt && checked.endAt) {
      request.startAt = checked.startAt;
      request.endAt = checked.endAt;
    }
    // mutateAsync: the refetch after saving re-mounts this form (key = version) before callbacks would run.
    update.mutateAsync(request).then(
      (result) =>
        navigate(`/treninzi/${training.trainingId}`, {
          state: {
            notice: !result.changed
              ? 'Nije bilo promjena.'
              : result.notificationStatus === 'QUEUED'
                ? 'Promjena je spremljena. Obavijest prijavljenima je stavljena u red za slanje.'
                : 'Promjena je spremljena.',
          } satisfies SavedNotice,
        }),
      (error: unknown) => {
        if (error instanceof ApiError && error.problem.errorCode === 'VERSION_CONFLICT') {
          setConflict(true);
          return;
        }
        const mapped = serverErrors(error);
        setErrors(mapped.errors);
        setFormError(mapped.message);
      },
    );
  };

  return (
    <>
      <PageHeader title="Izmjena jednog termina" description={formatLocalDate(training.localDate)} />
      <div className="mb-4">
        <Alert tone="info" title="Mijenjate samo ovaj termin">
          {training.scheduleId ? (
            <>
              Ostali termini tjednog rasporeda ostaju nepromijenjeni, a kasnije izmjene pravila više ne pomiču ovaj termin. Za
              promjenu svih budućih termina uredite{' '}
              <Link to="/treninzi/raspored" className="font-semibold underline underline-offset-2">
                tjedni raspored
              </Link>
              .
            </>
          ) : (
            'Ovo je izvanredni trening izvan tjednog rasporeda.'
          )}
        </Alert>
      </div>
      <form noValidate onSubmit={submit} className="flex flex-col gap-4">
        {conflict && (
          <Alert tone="error" title="Termin je u međuvremenu izmijenjen">
            <p>Vaša promjena nije spremljena. Učitajte najnovije podatke i ponovite izmjenu.</p>
            <Button variant="secondary" className="mt-2" onClick={onReload}>
              Učitaj najnovije
            </Button>
          </Alert>
        )}
        {formError && <Alert tone="error">{formError}</Alert>}
        {!timesEditable && (
          <Alert tone="warning">
            Trening je započeo ili je održan, pa se vrijeme više ne može mijenjati. Naziv, napomenu i trenere i dalje možete ispraviti.
          </Alert>
        )}
        <TrainingFields values={values} set={set} errors={errors} timesEditable={timesEditable} showHistorical={false} />
        <div className="flex flex-wrap gap-2">
          <Button type="submit" loading={update.isPending}>
            Spremi samo ovaj termin
          </Button>
          <Link
            to={`/treninzi/${training.trainingId}`}
            className="inline-flex min-h-11 items-center px-4 text-sm font-semibold underline underline-offset-2"
          >
            Odustani
          </Link>
        </div>
      </form>
    </>
  );
}
