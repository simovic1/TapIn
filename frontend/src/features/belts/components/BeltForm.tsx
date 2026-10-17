import { useId, useState, type FormEvent } from 'react';
import { Alert } from '../../../components/ui/Alert';
import { Button } from '../../../components/ui/Button';
import { TextArea } from '../../../components/ui/TextArea';
import { TextField } from '../../../components/ui/TextField';
import { ApiError } from '../../../lib/api/client';
import { errorMessage, fieldErrors } from '../../../lib/api/errors';
import { addDays, clubToday } from '../../../lib/format';
import {
  BELT_LABELS,
  BELTS,
  type Belt,
  type BeltPeriod,
  type BeltPeriodMutationResult,
  type PromotionTiming,
} from '../api';
import { shortDate } from './BeltHistoryView';

export interface BeltFormValues {
  belt: Belt;
  awardedKnown: boolean;
  awardedOn: string;
  timing: PromotionTiming;
  effectiveFromDate: string;
  note: string;
  reason: string;
}

type Errors = Partial<Record<'awardedOn' | 'effectiveFromDate' | 'reason', string>>;

export const emptyValues = (): BeltFormValues => ({
  belt: 'WHITE',
  awardedKnown: true,
  awardedOn: clubToday(),
  timing: 'AFTER_TRAINING',
  effectiveFromDate: '',
  note: '',
  reason: '',
});

export function valuesOf(p: BeltPeriod): BeltFormValues {
  return {
    belt: p.belt,
    awardedKnown: p.awardedOn !== null,
    awardedOn: p.awardedOn ?? '',
    timing: p.awardedOn !== null && p.awardedOn === p.effectiveFromDate ? 'BEFORE_TRAINING' : 'AFTER_TRAINING',
    effectiveFromDate: p.effectiveFromDate,
    note: p.note ?? '',
    reason: '',
  };
}

/** A white belt entered without any date counts from the member's join date (the server applies the same rule). */
const defaultsToJoinDate = (v: BeltFormValues, correction: boolean) =>
  !correction && !v.awardedKnown && v.belt === 'WHITE' && !v.effectiveFromDate;

/** Start of counting implied by the form (shown before saving, spec §3.3). */
export function countingStart(v: BeltFormValues, memberSince?: string | null, correction = false): string | null {
  if (defaultsToJoinDate(v, correction)) return memberSince ?? null;
  if (!v.awardedKnown) return v.effectiveFromDate || null;
  if (!/^\d{4}-\d{2}-\d{2}$/.test(v.awardedOn)) return null;
  return v.timing === 'AFTER_TRAINING' ? addDays(v.awardedOn, 1) : v.awardedOn;
}

const DATE = /^\d{4}-\d{2}-\d{2}$/;

function validate(v: BeltFormValues, correction: boolean): Errors {
  const errors: Errors = {};
  const today = clubToday();
  if (v.awardedKnown) {
    if (!DATE.test(v.awardedOn)) errors.awardedOn = 'Unesite datum dodjele ili označite da nije poznat.';
    else if (v.awardedOn > today) errors.awardedOn = 'Datum dodjele ne može biti u budućnosti.';
  } else if (defaultsToJoinDate(v, correction)) {
    // empty is fine: counted from the join date
  } else if (!DATE.test(v.effectiveFromDate)) {
    errors.effectiveFromDate = 'Unesite od kada se treninzi broje u ovaj pojas.';
  } else if (v.effectiveFromDate > today) {
    errors.effectiveFromDate = 'Početak brojanja ne može biti u budućnosti.';
  }
  if (correction && !v.reason.trim()) errors.reason = 'Upišite razlog ispravka (zapisuje se u povijest ispravaka).';
  return errors;
}

/**
 * Initial entry, promotion or correction of a belt period. Separates the real award date from the start of counting
 * and promotion before/after training; unknown historical dates stay unknown (AC-27). Success is reported only after
 * the server confirmed it; a 409 asks the trainer to decide again on the fresh state.
 */
export function BeltForm({
  title,
  initial,
  correction,
  submitLabel,
  onSubmit,
  onDone,
  onCancel,
  onConflict,
  memberSince,
}: {
  title: string;
  initial: BeltFormValues;
  correction: boolean;
  submitLabel: string;
  onSubmit: (values: BeltFormValues) => Promise<BeltPeriodMutationResult>;
  onDone: (result: BeltPeriodMutationResult) => void;
  onCancel: () => void;
  /** 409 VERSION_CONFLICT with the fresh period from the server. */
  onConflict?: (fresh: BeltPeriod | undefined) => void;
  /** Member's join date: the default start of a white belt entered without dates. */
  memberSince?: string | null;
}) {
  const [values, setValues] = useState(initial);
  const [errors, setErrors] = useState<Errors>({});
  const [formError, setFormError] = useState<string>();
  const [saving, setSaving] = useState(false);
  const timingName = useId();
  const set = (patch: Partial<BeltFormValues>) => setValues((v) => ({ ...v, ...patch }));
  const start = countingStart(values, memberSince, correction);
  const fromJoinDate = defaultsToJoinDate(values, correction);

  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (saving) return;
    const found = validate(values, correction);
    setErrors(found);
    setFormError(undefined);
    if (Object.keys(found).length > 0) return;
    setSaving(true);
    onSubmit(values)
      .then(onDone, (error: unknown) => {
        if (error instanceof ApiError && error.problem.errorCode === 'VERSION_CONFLICT') {
          onConflict?.(error.problem.currentState as BeltPeriod | undefined);
          return;
        }
        const fields = fieldErrors(error);
        const mapped: Errors = {};
        if (fields.awardedOn) mapped.awardedOn = fields.awardedOn;
        if (fields.effectiveFromDate) mapped.effectiveFromDate = fields.effectiveFromDate;
        if (error instanceof ApiError && error.problem.errorCode === 'BELT_DUPLICATE_EFFECTIVE_DATE') {
          mapped[values.awardedKnown ? 'awardedOn' : 'effectiveFromDate'] = errorMessage(error);
        }
        setErrors(mapped);
        setFormError(`Nije spremljeno. ${errorMessage(error)}`);
      })
      .finally(() => setSaving(false));
  };

  return (
    <form noValidate onSubmit={submit} aria-label={title} className="flex flex-col gap-4 rounded-2xl border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900">
      <h3 className="font-semibold">{title}</h3>
      {formError && <Alert tone="error">{formError}</Alert>}
      <div className="flex flex-col gap-1">
        <label htmlFor={`${timingName}-belt`} className="text-sm font-medium">
          Pojas
        </label>
        <select
          id={`${timingName}-belt`}
          value={values.belt}
          onChange={(e) => set({ belt: e.target.value as Belt })}
          className="min-h-11 rounded-lg border border-slate-300 bg-white px-3 text-base dark:border-slate-700 dark:bg-slate-950"
        >
          {BELTS.map((b) => (
            <option key={b} value={b}>
              {BELT_LABELS[b]}
            </option>
          ))}
        </select>
      </div>

      <label className="flex min-h-11 items-center gap-3">
        <input type="checkbox" className="size-5" checked={!values.awardedKnown} onChange={(e) => set({ awardedKnown: !e.target.checked })} />
        <span>Datum dodjele nije poznat (stariji pojas)</span>
      </label>

      {values.awardedKnown ? (
        <>
          <TextField
            label="Datum dodjele (stvarni datum polaganja)"
            type="date"
            required
            max={clubToday()}
            value={values.awardedOn}
            onChange={(e) => set({ awardedOn: e.target.value })}
            error={errors.awardedOn}
          />
          <fieldset className="flex flex-col gap-2">
            <legend className="text-sm font-medium">Kada je pojas dodijeljen?</legend>
            <label className="flex min-h-11 items-start gap-3 rounded-lg border border-slate-200 p-3 dark:border-slate-800">
              <input
                type="radio"
                name={timingName}
                className="mt-1 size-5"
                checked={values.timing === 'AFTER_TRAINING'}
                onChange={() => set({ timing: 'AFTER_TRAINING' })}
              />
              <span>
                <span className="font-medium">Nakon treninga</span>
                <span className="block text-sm text-slate-600 dark:text-slate-400">
                  Trening toga dana ostaje u prethodnom pojasu; novi pojas broji se od sljedećeg dana.
                </span>
              </span>
            </label>
            <label className="flex min-h-11 items-start gap-3 rounded-lg border border-slate-200 p-3 dark:border-slate-800">
              <input
                type="radio"
                name={timingName}
                className="mt-1 size-5"
                checked={values.timing === 'BEFORE_TRAINING'}
                onChange={() => set({ timing: 'BEFORE_TRAINING' })}
              />
              <span>
                <span className="font-medium">Prije treninga</span>
                <span className="block text-sm text-slate-600 dark:text-slate-400">
                  Trening toga dana već se broji u novi pojas.
                </span>
              </span>
            </label>
          </fieldset>
        </>
      ) : (
        <TextField
          label="Treninzi se broje od"
          type="date"
          required={correction || values.belt !== 'WHITE'}
          max={clubToday()}
          value={values.effectiveFromDate}
          onChange={(e) => set({ effectiveFromDate: e.target.value })}
          error={errors.effectiveFromDate}
          hint={
            !correction && values.belt === 'WHITE'
              ? `Ako ostavite prazno, bijeli pojas broji se od datuma učlanjenja${memberSince ? ` (${shortDate(memberSince)})` : ''}. Datum dodjele ostaje „nije evidentiran”.`
              : 'Datum dodjele ostaje „nije evidentiran”.'
          }
        />
      )}
      <p className="text-sm" aria-live="polite">
        Početak brojanja treninga: <span className="font-semibold">{start ? shortDate(start) : '–'}</span>
        {values.awardedKnown && start && ' (datum dodjele prikazuje se zasebno)'}
        {fromJoinDate && start && ' (datum učlanjenja)'}
      </p>
      <TextArea label="Napomena" maxLength={500} value={values.note} onChange={(e) => set({ note: e.target.value })} />
      {correction && (
        <TextField
          label="Razlog ispravka"
          required
          maxLength={500}
          value={values.reason}
          onChange={(e) => set({ reason: e.target.value })}
          error={errors.reason}
        />
      )}
      <div className="flex flex-wrap gap-2">
        <Button type="submit" loading={saving}>
          {submitLabel}
        </Button>
        <Button variant="ghost" onClick={onCancel} disabled={saving}>
          Odustani
        </Button>
      </div>
    </form>
  );
}
