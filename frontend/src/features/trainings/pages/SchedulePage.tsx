import { useMemo, useState, type FormEvent } from 'react';
import { Link } from 'react-router';
import { Alert } from '../../../components/ui/Alert';
import { Button } from '../../../components/ui/Button';
import { PageHeader } from '../../../components/ui/PageHeader';
import { QueryState } from '../../../components/ui/QueryState';
import { TextField } from '../../../components/ui/TextField';
import { ApiError } from '../../../lib/api/client';
import { errorMessage, fieldErrors } from '../../../lib/api/errors';
import { clubDateTimeToIso, clubToday, formatLocalDate, formatTimeRange, toClubParts } from '../../../lib/format';
import {
  useCreateSchedule,
  useRecurringSchedules,
  useTrainings,
  useUpdateSchedule,
  type RecurringSchedule,
  type TrainingSummary,
  type UpdateRecurringScheduleRequest,
  type Weekday,
} from '../api';

export const WEEKDAYS: { value: Weekday; label: string }[] = [
  { value: 'MONDAY', label: 'Ponedjeljak' },
  { value: 'TUESDAY', label: 'Utorak' },
  { value: 'WEDNESDAY', label: 'Srijeda' },
  { value: 'THURSDAY', label: 'Četvrtak' },
  { value: 'FRIDAY', label: 'Petak' },
  { value: 'SATURDAY', label: 'Subota' },
  { value: 'SUNDAY', label: 'Nedjelja' },
];
const weekdayLabel = (w: Weekday) => WEEKDAYS.find((d) => d.value === w)?.label ?? w;
const shortDate = (date: string) => formatLocalDate(date, { weekday: undefined });
const TIME = /^([01]\d|2[0-3]):[0-5]\d$/;

type RuleErrors = Partial<Record<'weekday' | 'startTime' | 'endTime' | 'validFrom' | 'validTo', string>>;

function validateRule(v: { startTime: string; endTime: string; validFrom: string; validTo: string }): RuleErrors {
  const errors: RuleErrors = {};
  if (!TIME.test(v.startTime)) errors.startTime = 'Unesite vrijeme početka (HH:mm).';
  if (!TIME.test(v.endTime)) errors.endTime = 'Unesite vrijeme završetka (HH:mm).';
  else if (TIME.test(v.startTime) && v.endTime <= v.startTime) errors.endTime = 'Završetak mora biti nakon početka.';
  if (!clubDateTimeToIso(v.validFrom, '12:00')) errors.validFrom = 'Unesite ispravan datum.';
  if (v.validTo && !clubDateTimeToIso(v.validTo, '12:00')) errors.validTo = 'Unesite ispravan datum.';
  else if (v.validTo && v.validTo < v.validFrom) errors.validTo = 'Datum završetka ne može biti prije datuma početka.';
  return errors;
}

function ruleServerErrors(error: unknown): { errors: RuleErrors; message: string } {
  const fields = fieldErrors(error) as RuleErrors;
  if (error instanceof ApiError && error.problem.errorCode === 'TRAINING_OVERLAP') {
    return { errors: { ...fields, startTime: 'Preklapa se s drugim pravilom ili treningom.' }, message: errorMessage(error) };
  }
  return { errors: fields, message: `Promjena nije spremljena. ${errorMessage(error)}` };
}

/** Weekly rules (spec §4.1, §12.2): changes here apply to all FUTURE sessions of a rule, never to the past. */
export function SchedulePage() {
  const schedules = useRecurringSchedules();
  const [adding, setAdding] = useState(false);
  const [notice, setNotice] = useState<string>();

  return (
    <section className="max-w-3xl">
      <p className="mb-2 text-sm">
        <Link to="/treninzi" className="underline underline-offset-2">
          ← Svi treninzi
        </Link>
      </p>
      <PageHeader
        title="Tjedni raspored"
        description="Pravila po kojima se termini stvaraju 60 dana unaprijed."
        actions={!adding && <Button onClick={() => { setNotice(undefined); setAdding(true); }}>Novo pravilo</Button>}
      />
      <div className="mb-4">
        <Alert tone="info" title="Izmjena pravila mijenja buduće termine">
          Promjena vrijedi za sve buduće termine pravila koji još nisu počeli i nisu pojedinačno mijenjani. Održani i prošli
          termini se ne mijenjaju. Za promjenu samo jednog datuma otvorite taj trening u{' '}
          <Link to="/treninzi" className="font-semibold underline underline-offset-2">
            kalendaru
          </Link>
          .
        </Alert>
      </div>
      {notice && (
        <div className="mb-4">
          <Alert tone="success">{notice}</Alert>
        </div>
      )}
      {adding && <NewRuleForm onDone={(message) => { setAdding(false); if (message) setNotice(message); }} />}
      <QueryState
        query={schedules}
        loadingLabel="Učitavanje rasporeda…"
        isEmpty={(items) => items.length === 0}
        empty={{ title: 'Nema tjednih pravila', description: 'Dodajte pravilo, npr. utorak 18:30–20:00.' }}
      >
        {(items) => (
          <ul className="flex flex-col gap-3">
            {items.map((rule) => (
              <li key={rule.scheduleId}>
                <RuleCard rule={rule} onSaved={setNotice} />
              </li>
            ))}
          </ul>
        )}
      </QueryState>
    </section>
  );
}

function RuleSummary({ rule }: { rule: RecurringSchedule }) {
  return (
    <div>
      <p className="font-semibold">
        {weekdayLabel(rule.weekday)} · <span className="tabular-nums">{rule.startTime}–{rule.endTime}</span>
      </p>
      <p className="text-sm text-slate-600 dark:text-slate-400">
        Od {shortDate(rule.validFrom)}
        {rule.validTo ? ` do ${shortDate(rule.validTo)}` : ', bez završetka'}
      </p>
    </div>
  );
}

function RuleCard({ rule, onSaved }: { rule: RecurringSchedule; onSaved: (message: string) => void }) {
  const [editing, setEditing] = useState(false);
  return (
    <article className="rounded-2xl border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <RuleSummary rule={rule} />
        <div className="flex items-center gap-2">
          <span
            className={`rounded-full px-2.5 py-0.5 text-xs font-semibold ${
              rule.enabled
                ? 'bg-green-100 text-green-900 dark:bg-green-950 dark:text-green-100'
                : 'bg-slate-200 text-slate-800 dark:bg-slate-800 dark:text-slate-100'
            }`}
          >
            {rule.enabled ? 'Aktivno' : 'Isključeno'}
          </span>
          {!editing && (
            <Button variant="secondary" onClick={() => setEditing(true)} aria-label={`Uredi pravilo ${weekdayLabel(rule.weekday)} ${rule.startTime}`}>
              Uredi pravilo
            </Button>
          )}
        </div>
      </div>
      {editing && (
        <EditRuleForm
          rule={rule}
          onDone={(message) => {
            setEditing(false);
            if (message) onSaved(message);
          }}
        />
      )}
    </article>
  );
}

/** Future sessions of the rule a change would touch (same rule as the backend, decision D-02). */
function useAffectedSessions(rule: RecurringSchedule, enabled: boolean) {
  const range = useMemo(() => {
    const now = Date.now();
    return { from: new Date(now).toISOString(), to: new Date(now + 90 * 86_400_000).toISOString() };
  }, []);
  const query = useTrainings(range);
  const sessions = (query.data ?? []).filter(
    (t) =>
      enabled &&
      t.scheduleId === rule.scheduleId &&
      t.status === 'SCHEDULED' &&
      Date.parse(t.startAt) > Date.now() &&
      // Individually moved sessions keep their time: only untouched occurrences follow the rule.
      t.localDate === t.occurrenceDate &&
      toClubParts(t.startAt).time === rule.startTime &&
      toClubParts(t.endAt).time === rule.endTime,
  );
  return { query, sessions };
}

interface RuleChange {
  startTime: string;
  endTime: string;
  validTo: string;
  enabled: boolean;
}

function EditRuleForm({ rule, onDone }: { rule: RecurringSchedule; onDone: (message?: string) => void }) {
  const update = useUpdateSchedule(rule.scheduleId);
  const [values, setValues] = useState<RuleChange>({
    startTime: rule.startTime,
    endTime: rule.endTime,
    validTo: rule.validTo ?? '',
    enabled: rule.enabled,
  });
  const [errors, setErrors] = useState<RuleErrors>({});
  const [formError, setFormError] = useState<string>();
  const [reviewing, setReviewing] = useState(false);
  const { query: affectedQuery, sessions } = useAffectedSessions(rule, true);
  const set = (patch: Partial<RuleChange>) => {
    setReviewing(false);
    setValues((v) => ({ ...v, ...patch }));
  };

  const willCancel = sessions.filter((t) => !values.enabled || (values.validTo !== '' && (t.occurrenceDate ?? '') > values.validTo));
  const timeChanged = values.startTime !== rule.startTime || values.endTime !== rule.endTime;
  const willMove = timeChanged ? sessions.filter((t) => !willCancel.includes(t)) : [];
  const changed = timeChanged || values.enabled !== rule.enabled || values.validTo !== (rule.validTo ?? '');

  const review = (event: FormEvent) => {
    event.preventDefault();
    const found = validateRule({ ...values, validFrom: rule.validFrom });
    setErrors(found);
    setFormError(undefined);
    if (Object.keys(found).length === 0) setReviewing(true);
  };

  const save = () => {
    const request: UpdateRecurringScheduleRequest = { expectedVersion: rule.version };
    if (values.startTime !== rule.startTime) request.startTime = values.startTime;
    if (values.endTime !== rule.endTime) request.endTime = values.endTime;
    if (values.validTo !== (rule.validTo ?? '')) request.validTo = values.validTo || null;
    if (values.enabled !== rule.enabled) request.enabled = values.enabled;
    // mutateAsync: the outcome is handled even if a refetch re-renders the list while saving.
    update.mutateAsync(request).then(
      () => onDone('Tjedno pravilo je spremljeno; kalendar je osvježen.'),
      (error: unknown) => {
        setReviewing(false);
        if (error instanceof ApiError && error.problem.errorCode === 'VERSION_CONFLICT') {
          setFormError('Pravilo je u međuvremenu izmijenjeno. Zatvorite obrazac i otvorite ga ponovno s najnovijim podacima.');
          return;
        }
        const mapped = ruleServerErrors(error);
        setErrors(mapped.errors);
        setFormError(mapped.message);
      },
    );
  };

  return (
    <form noValidate onSubmit={review} className="mt-4 flex flex-col gap-4 border-t border-slate-200 pt-4 dark:border-slate-800">
      <h3 className="font-semibold">Izmjena pravila za sve buduće termine ({weekdayLabel(rule.weekday)})</h3>
      {formError && <Alert tone="error">{formError}</Alert>}
      <div className="grid gap-4 sm:grid-cols-3">
        <TextField label="Početak" type="time" required value={values.startTime} onChange={(e) => set({ startTime: e.target.value })} error={errors.startTime} />
        <TextField label="Završetak" type="time" required value={values.endTime} onChange={(e) => set({ endTime: e.target.value })} error={errors.endTime} />
        <TextField
          label="Vrijedi do"
          type="date"
          value={values.validTo}
          min={rule.validFrom}
          onChange={(e) => set({ validTo: e.target.value })}
          error={errors.validTo}
          hint="Prazno = bez završetka."
        />
      </div>
      <label className="flex min-h-11 items-center gap-3">
        <input type="checkbox" className="size-5" checked={values.enabled} onChange={(e) => set({ enabled: e.target.checked })} />
        <span>Pravilo je aktivno</span>
      </label>

      {reviewing && (
        <div className="flex flex-col gap-2 rounded-xl border border-amber-300 bg-amber-50 p-4 text-sm dark:border-amber-800 dark:bg-amber-950/40" role="status">
          <p className="font-semibold">Pregled promjene</p>
          {affectedQuery.isPending && <p>Provjera budućih termina…</p>}
          {affectedQuery.isError && <p>Popis budućih termina nije dostupan; promjena će se ipak primijeniti prema pravilu.</p>}
          {!changed && <p>Nema promjena.</p>}
          {willCancel.length > 0 && (
            <div>
              <p className="font-semibold text-red-800 dark:text-red-300">Bit će otkazano ({willCancel.length}):</p>
              <SessionDates sessions={willCancel} />
              <p>Termini se ne brišu, a prijavljeni članovi dobit će obavijest.</p>
            </div>
          )}
          {willMove.length > 0 && (
            <div>
              <p className="font-semibold">
                Novo vrijeme {values.startTime}–{values.endTime} dobiva {willMove.length} {willMove.length === 1 ? 'termin' : 'termina'}:
              </p>
              <SessionDates sessions={willMove} />
            </div>
          )}
          {changed && willCancel.length === 0 && willMove.length === 0 && affectedQuery.isSuccess && (
            <p>Već zakazani termini se ne mijenjaju; promjena vrijedi za termine koji tek nastaju.</p>
          )}
          <p>Pojedinačno mijenjani i održani termini ostaju kakvi jesu.</p>
        </div>
      )}

      <div className="flex flex-wrap gap-2">
        {reviewing ? (
          <Button onClick={save} loading={update.isPending} disabled={!changed}>
            Potvrdi izmjenu pravila
          </Button>
        ) : (
          <Button type="submit">Pregledaj promjenu</Button>
        )}
        <Button variant="ghost" onClick={() => onDone()} disabled={update.isPending}>
          Odustani
        </Button>
      </div>
    </form>
  );
}

function SessionDates({ sessions }: { sessions: TrainingSummary[] }) {
  return (
    <ul className="mt-1 list-disc pl-5">
      {sessions.map((t) => (
        <li key={t.trainingId}>
          <span className="first-letter:uppercase">{formatLocalDate(t.localDate)}</span>, {formatTimeRange(t.startAt, t.endAt)}
        </li>
      ))}
    </ul>
  );
}

function NewRuleForm({ onDone }: { onDone: (message?: string) => void }) {
  const create = useCreateSchedule();
  const [values, setValues] = useState({ weekday: 'TUESDAY' as Weekday, startTime: '18:30', endTime: '20:00', validFrom: clubToday(), validTo: '' });
  const [errors, setErrors] = useState<RuleErrors>({});
  const [formError, setFormError] = useState<string>();
  const set = (patch: Partial<typeof values>) => setValues((v) => ({ ...v, ...patch }));

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const found = validateRule(values);
    setErrors(found);
    setFormError(undefined);
    if (Object.keys(found).length > 0) return;
    create.mutateAsync({ ...values, validTo: values.validTo || null, enabled: true }).then(
      () => onDone('Novo pravilo je spremljeno; termini su dodani u kalendar.'),
      (error: unknown) => {
        const mapped = ruleServerErrors(error);
        setErrors(mapped.errors);
        setFormError(mapped.message);
      },
    );
  };

  return (
    <form noValidate onSubmit={submit} className="mb-4 flex flex-col gap-4 rounded-2xl border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900">
      <h2 className="font-semibold">Novo tjedno pravilo</h2>
      {formError && <Alert tone="error">{formError}</Alert>}
      <div className="flex flex-col gap-1">
        <label htmlFor="rule-weekday" className="text-sm font-medium">
          Dan u tjednu
        </label>
        <select
          id="rule-weekday"
          value={values.weekday}
          onChange={(e) => set({ weekday: e.target.value as Weekday })}
          className="min-h-11 rounded-lg border border-slate-300 bg-white px-3 text-base dark:border-slate-700 dark:bg-slate-950"
        >
          {WEEKDAYS.map((d) => (
            <option key={d.value} value={d.value}>
              {d.label}
            </option>
          ))}
        </select>
      </div>
      <div className="grid gap-4 sm:grid-cols-2">
        <TextField label="Početak" type="time" required value={values.startTime} onChange={(e) => set({ startTime: e.target.value })} error={errors.startTime} />
        <TextField label="Završetak" type="time" required value={values.endTime} onChange={(e) => set({ endTime: e.target.value })} error={errors.endTime} />
        <TextField label="Vrijedi od" type="date" required value={values.validFrom} onChange={(e) => set({ validFrom: e.target.value })} error={errors.validFrom} />
        <TextField label="Vrijedi do" type="date" value={values.validTo} onChange={(e) => set({ validTo: e.target.value })} error={errors.validTo} hint="Prazno = bez završetka." />
      </div>
      <div className="flex flex-wrap gap-2">
        <Button type="submit" loading={create.isPending}>
          Spremi pravilo
        </Button>
        <Button variant="ghost" onClick={() => onDone()} disabled={create.isPending}>
          Odustani
        </Button>
      </div>
    </form>
  );
}
