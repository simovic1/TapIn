import { useSystemInfo } from '../lib/api/system';
import { formatDateTime } from '../lib/format';

export function HomePage() {
  const { data, isPending, isError, refetch } = useSystemInfo();

  return (
    <section className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold">Dobrodošli u TapIn</h1>
        <p className="text-slate-600 dark:text-slate-400">
          Evidencija BJJ treninga – prijave, prozivka, pojasevi i statistika.
        </p>
      </div>

      <div className="rounded-2xl border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900">
        <h2 className="mb-2 text-sm font-semibold tracking-wide text-slate-500 uppercase">Stanje sustava</h2>
        {isPending && <p className="text-slate-500">Provjera veze s poslužiteljem…</p>}
        {isError && (
          <div className="flex flex-wrap items-center gap-3">
            <span className="text-red-600 dark:text-red-400">Poslužitelj nije dostupan.</span>
            <button
              type="button"
              onClick={() => refetch()}
              className="min-h-11 rounded-lg border border-slate-300 px-4 text-sm font-medium dark:border-slate-700"
            >
              Pokušaj ponovno
            </button>
          </div>
        )}
        {data && (
          <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-sm">
            <dt className="text-slate-500">Klub</dt>
            <dd>{data.clubName}</dd>
            <dt className="text-slate-500">Vremenska zona</dt>
            <dd>{data.timeZone}</dd>
            <dt className="text-slate-500">Vrijeme poslužitelja</dt>
            <dd>{formatDateTime(data.serverTime)}</dd>
            <dt className="text-slate-500">Verzija</dt>
            <dd>{data.version}</dd>
          </dl>
        )}
      </div>
    </section>
  );
}
