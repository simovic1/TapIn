import { EmptyState } from './EmptyState';
import { PageHeader } from './PageHeader';

interface PlaceholderPageProps {
  title: string;
  phase: string;
  description: string;
}

/** Temporary page for features that are scheduled in a later backlog phase (spec §16). */
export function PlaceholderPage({ title, phase, description }: PlaceholderPageProps) {
  return (
    <section>
      <PageHeader title={title} description={description} />
      <EmptyState title="U izradi" description={`Ova stranica dolazi u fazi: ${phase}.`} />
    </section>
  );
}
