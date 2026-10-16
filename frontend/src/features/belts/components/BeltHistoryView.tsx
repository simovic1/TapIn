import type { ReactNode } from 'react';
import { formatLocalDate } from '../../../lib/format';
import { BELT_LABELS, type BeltHistory, type BeltPeriod } from '../api';

const BELT_SWATCH: Record<string, string> = {
  WHITE: 'bg-white border-slate-400',
  BLUE: 'bg-blue-700 border-blue-900',
  PURPLE: 'bg-purple-700 border-purple-900',
  BROWN: 'bg-amber-900 border-amber-950',
  BLACK: 'bg-black border-black',
  UNKNOWN: 'bg-transparent border-dashed border-slate-400',
};

export const shortDate = (date: string) => formatLocalDate(date, { weekday: undefined });

/** "datum nije evidentiran" for unknown award dates (AC-27) - never an invented date. */
export const awardedLabel = (awardedOn: string | null) => (awardedOn ? shortDate(awardedOn) : 'datum nije evidentiran');

export function BeltSwatch({ belt }: { belt: string }) {
  return <span aria-hidden="true" className={`inline-block h-3 w-8 rounded-sm border ${BELT_SWATCH[belt] ?? ''}`} />;
}

/**
 * Current belt, award date, trainings in this belt and in total, and every period with its own count (spec §3.3).
 * The sum of the periods plus "unassigned" always equals the total (§9.3).
 */
export function BeltHistoryView({ history, actions }: { history: BeltHistory; actions?: (period: BeltPeriod) => ReactNode }) {
  return (
    <div className="flex flex-col gap-4">
      <dl className="grid grid-cols-2 gap-3 rounded-2xl border border-slate-200 bg-white p-4 sm:grid-cols-4 dark:border-slate-800 dark:bg-slate-900">
        <div>
          <dt className="text-xs text-slate-500 dark:text-slate-400">Trenutačni pojas</dt>
          <dd className="flex items-center gap-2 font-semibold">
            <BeltSwatch belt={history.currentBelt} />
            {BELT_LABELS[history.currentBelt]}
          </dd>
        </div>
        <div>
          <dt className="text-xs text-slate-500 dark:text-slate-400">Datum dobivanja</dt>
          <dd>{history.currentBelt === 'UNKNOWN' ? '–' : awardedLabel(history.currentBeltAwardedOn)}</dd>
        </div>
        <div>
          <dt className="text-xs text-slate-500 dark:text-slate-400">Treninzi u tom pojasu</dt>
          <dd className="text-xl font-semibold tabular-nums">{history.currentBeltTrainings}</dd>
        </div>
        <div>
          <dt className="text-xs text-slate-500 dark:text-slate-400">Ukupno treninga</dt>
          <dd className="text-xl font-semibold tabular-nums">{history.totalTrainings}</dd>
        </div>
      </dl>

      {history.periods.length === 0 ? (
        <p className="rounded-xl border border-dashed border-slate-300 p-4 text-sm dark:border-slate-700">
          Pojas još nije evidentiran.
        </p>
      ) : (
        <ol className="flex flex-col gap-2" aria-label="Povijest pojaseva">
          {[...history.periods].reverse().map((p) => (
            <li key={p.beltPeriodId} className="rounded-xl border border-slate-200 bg-white p-3 dark:border-slate-800 dark:bg-slate-900">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <p className="flex items-center gap-2 font-semibold">
                  <BeltSwatch belt={p.belt} />
                  {BELT_LABELS[p.belt]} pojas
                </p>
                <p className="text-sm">
                  Treninga: <span className="font-semibold tabular-nums">{p.trainingsCount}</span>
                </p>
              </div>
              <dl className="mt-1 grid gap-x-4 text-sm text-slate-600 sm:grid-cols-2 dark:text-slate-400">
                <div>
                  <dt className="inline">Dodijeljen: </dt>
                  <dd className="inline">{awardedLabel(p.awardedOn)}</dd>
                </div>
                <div>
                  <dt className="inline">Treninzi se broje: </dt>
                  <dd className="inline">
                    od {shortDate(p.effectiveFromDate)}
                    {p.effectiveToDate ? ` do ${shortDate(p.effectiveToDate)} (isključivo)` : ' (trenutačni)'}
                  </dd>
                </div>
                {p.note && (
                  <div className="sm:col-span-2">
                    <dt className="inline">Napomena: </dt>
                    <dd className="inline">{p.note}</dd>
                  </div>
                )}
              </dl>
              {actions?.(p)}
            </li>
          ))}
        </ol>
      )}
      {history.unassignedTrainings > 0 && (
        <p className="text-sm text-slate-600 dark:text-slate-400">
          Neraspoređeni po pojasu (prije prvog poznatog pojasa): <span className="font-semibold">{history.unassignedTrainings}</span>
        </p>
      )}
    </div>
  );
}
