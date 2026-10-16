import { PageHeader } from '../components/ui/PageHeader';
import { QueryState } from '../components/ui/QueryState';
import { useSystemInfo } from '../lib/api/system';
import { formatDateTime } from '../lib/format';
import { ROLE_LABELS } from '../lib/session/roles';
import { useCurrentUser } from '../lib/session/session';

const card = 'rounded-2xl border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900';
const cardTitle = 'mb-2 text-sm font-semibold tracking-wide text-slate-500 uppercase dark:text-slate-400';

export function HomePage() {
  const { data: user } = useCurrentUser();
  const systemInfo = useSystemInfo();

  return (
    <section>
      <PageHeader
        title={user ? `Bok, ${user.firstName}!` : 'Dobrodošli u TapIn'}
        description="Evidencija BJJ treninga – prijave, prozivka, pojasevi i statistika."
      />

      <div className="grid gap-4 md:grid-cols-2">
        <section aria-labelledby="home-account" className={card}>
          <h2 id="home-account" className={cardTitle}>
            Vaš račun
          </h2>
          {user && (
            <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-sm">
              <dt className="text-slate-500 dark:text-slate-400">E-mail</dt>
              <dd className="break-all">{user.email}</dd>
              <dt className="text-slate-500 dark:text-slate-400">Uloge</dt>
              <dd>{user.roles.map((role) => ROLE_LABELS[role]).join(', ')}</dd>
              <dt className="text-slate-500 dark:text-slate-400">Klub</dt>
              <dd>{user.club.name}</dd>
            </dl>
          )}
        </section>

        <section aria-labelledby="home-system" className={card}>
          <h2 id="home-system" className={cardTitle}>
            Stanje sustava
          </h2>
          <QueryState query={systemInfo} loadingLabel="Provjera veze s poslužiteljem…">
            {(info) => (
              <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-sm">
                <dt className="text-slate-500 dark:text-slate-400">Vremenska zona</dt>
                <dd>{info.timeZone}</dd>
                <dt className="text-slate-500 dark:text-slate-400">Vrijeme poslužitelja</dt>
                <dd>{formatDateTime(info.serverTime)}</dd>
                <dt className="text-slate-500 dark:text-slate-400">Verzija</dt>
                <dd>{info.version}</dd>
              </dl>
            )}
          </QueryState>
        </section>
      </div>
    </section>
  );
}
