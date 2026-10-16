import { PageHeader } from '../components/ui/PageHeader';
import { NextTrainings } from '../features/trainings/components/NextTrainings';
import { ROLE_LABELS } from '../lib/session/roles';
import { useCurrentUser } from '../lib/session/session';

const card = 'rounded-2xl border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900';
const cardTitle = 'mb-2 text-sm font-semibold tracking-wide text-slate-500 uppercase dark:text-slate-400';

export function HomePage() {
  const { data: user } = useCurrentUser();

  return (
    <section>
      <PageHeader
        title={user ? `Bok, ${user.firstName}!` : 'Dobrodošli u TapIn'}
        description="Sljedeći treninzi i vaše prijave. Vremena su prikazana po zagrebačkom vremenu."
      />

      <NextTrainings />

      <section aria-labelledby="home-account" className={`${card} mt-4`}>
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
    </section>
  );
}
