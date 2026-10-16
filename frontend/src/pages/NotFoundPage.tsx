import { Link } from 'react-router';
import { PageHeader } from '../components/ui/PageHeader';

export function NotFoundPage() {
  return (
    <section>
      <PageHeader title="Stranica ne postoji" />
      <Link to="/" className="font-medium text-blue-700 underline-offset-4 hover:underline dark:text-blue-400">
        Natrag na početnu
      </Link>
    </section>
  );
}
