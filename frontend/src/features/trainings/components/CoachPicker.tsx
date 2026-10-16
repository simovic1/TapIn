import { useId } from 'react';
import { ErrorState } from '../../../components/ui/ErrorState';
import { LoadingState } from '../../../components/ui/LoadingState';
import { useCoachCandidates } from '../api';

/** Several coaches per session (spec §4.2); the list contains active trainers and administrators. */
export function CoachPicker({ value, onChange }: { value: number[]; onChange: (ids: number[]) => void }) {
  const coaches = useCoachCandidates();
  const legendId = useId();

  if (coaches.isPending) return <LoadingState label="Učitavanje trenera…" />;
  if (coaches.isError) return <ErrorState error={coaches.error} title="Popis trenera nije dostupan" onRetry={() => coaches.refetch()} />;

  const toggle = (id: number) => onChange(value.includes(id) ? value.filter((v) => v !== id) : [...value, id]);
  return (
    <fieldset aria-labelledby={legendId} className="flex flex-col gap-1">
      <legend id={legendId} className="text-sm font-medium">
        Treneri
      </legend>
      <p className="text-sm text-slate-500 dark:text-slate-400">
        Informativno – svaki trener može voditi evidenciju za svaki trening.
      </p>
      <div className="mt-1 grid gap-1 sm:grid-cols-2">
        {coaches.data.map((c) => (
          <label
            key={c.membershipId}
            className="flex min-h-11 items-center gap-3 rounded-lg border border-slate-200 px-3 dark:border-slate-800"
          >
            <input
              type="checkbox"
              className="size-5"
              checked={value.includes(c.membershipId)}
              onChange={() => toggle(c.membershipId)}
            />
            <span>
              {c.firstName} {c.lastName}
            </span>
          </label>
        ))}
      </div>
    </fieldset>
  );
}
