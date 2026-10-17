import { QueryState } from '../../../components/ui/QueryState';
import { BELT_LABELS, useBeltHistory, type BeltHistory } from '../api';
import { awardedLabel, BeltSwatch, shortDate } from './BeltHistoryView';

/**
 * "Moj profil" belt progress (spec §3.3, §12.1): current belt, award date, trainings in it and in total, and a table
 * of every belt with its period and trainings. Read-only for the member (AC-28); no stripes, no ranking.
 * Three different "unknowns" have three different labels: no belt history at all, an unknown award date, and
 * trainings before the first known belt.
 */
export function OwnBeltProgress() {
  const history = useBeltHistory('me');
  return (
    <QueryState query={history} loadingLabel="Učitavanje pojaseva…">
      {(data) => <Progress history={data} />}
    </QueryState>
  );
}

function Progress({ history }: { history: BeltHistory }) {
  const unknownBelt = history.currentBelt === 'UNKNOWN';
  return (
    <div className="flex flex-col gap-4">
      <dl className="grid grid-cols-2 gap-3 rounded-2xl border border-slate-200 bg-white p-4 sm:grid-cols-4 dark:border-slate-800 dark:bg-slate-900">
        <div>
          <dt className="text-xs text-slate-500 dark:text-slate-400">Trenutačni pojas</dt>
          <dd className="flex items-center gap-2 font-semibold">
            <BeltSwatch belt={history.currentBelt} />
            {unknownBelt ? 'Još nije evidentiran' : BELT_LABELS[history.currentBelt]}
          </dd>
        </div>
        <div>
          <dt className="text-xs text-slate-500 dark:text-slate-400">Datum dobivanja</dt>
          <dd>{unknownBelt ? '–' : awardedLabel(history.currentBeltAwardedOn)}</dd>
        </div>
        <div>
          <dt className="text-xs text-slate-500 dark:text-slate-400">Treninzi u ovom pojasu</dt>
          <dd className="font-display text-3xl leading-tight font-bold tabular-nums">{unknownBelt ? '–' : history.currentBeltTrainings}</dd>
        </div>
        <div>
          <dt className="text-xs text-slate-500 dark:text-slate-400">Ukupno treninga</dt>
          <dd className="font-display text-3xl leading-tight font-bold tabular-nums">{history.totalTrainings}</dd>
        </div>
      </dl>

      {unknownBelt ? (
        <p className="text-sm text-slate-600 dark:text-slate-400">
          Trener još nije unio vaš pojas. Kad ga unese, ovdje ćete vidjeti povijest pojaseva i broj treninga u svakom.
        </p>
      ) : (
        <table className="w-full table-fixed border-separate border-spacing-0 overflow-hidden rounded-xl border border-slate-200 text-sm dark:border-slate-800">
          <caption className="mb-2 text-left font-semibold">Povijest pojaseva</caption>
          <thead className="bg-slate-50 text-left dark:bg-slate-800/60">
            <tr>
              <th scope="col" className="p-2">Pojas i razdoblje</th>
              <th scope="col" className="w-[34%] p-2">Dodijeljen</th>
              <th scope="col" className="w-[22%] p-2 text-right">Treninzi</th>
            </tr>
          </thead>
          <tbody>
            {[...history.periods].reverse().map((p) => (
              <tr key={p.beltPeriodId} className="align-top">
                <td className="border-t border-slate-200 p-2 dark:border-slate-800">
                  <span className="flex items-center gap-2 font-medium">
                    <BeltSwatch belt={p.belt} />
                    {BELT_LABELS[p.belt]}
                  </span>
                  <span className="block text-xs text-slate-600 dark:text-slate-400">
                    od {shortDate(p.effectiveFromDate)}
                    {p.effectiveToDate ? ` do ${shortDate(p.effectiveToDate)}` : ' – danas'}
                  </span>
                </td>
                <td className="border-t border-slate-200 p-2 dark:border-slate-800">{awardedLabel(p.awardedOn)}</td>
                <td className="border-t border-slate-200 p-2 text-right font-semibold tabular-nums dark:border-slate-800">
                  {p.trainingsCount}
                </td>
              </tr>
            ))}
            {history.unassignedTrainings > 0 && (
              <tr>
                <td colSpan={2} className="border-t border-slate-200 p-2 dark:border-slate-800">
                  Neraspoređeni dolasci
                  <span className="block text-xs text-slate-600 dark:text-slate-400">prije prvog poznatog pojasa</span>
                </td>
                <td className="border-t border-slate-200 p-2 text-right font-semibold tabular-nums dark:border-slate-800">
                  {history.unassignedTrainings}
                </td>
              </tr>
            )}
          </tbody>
        </table>
      )}
      {unknownBelt && history.totalTrainings > 0 && (
        <p className="text-sm">
          Neraspoređeni dolasci (još bez pojasa): <span className="font-semibold">{history.unassignedTrainings}</span>
        </p>
      )}
    </div>
  );
}
