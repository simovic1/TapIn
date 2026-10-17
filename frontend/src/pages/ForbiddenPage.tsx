import { Link } from 'react-router';
import { PageHeader } from '../components/ui/PageHeader';

export function ForbiddenPage() {
  return (
    <section>
      <PageHeader title="Nemate pristup" description="Ova stranica nije dostupna vašim ulogama." />
      <Link to="/" className="font-medium text-dragon-700 underline-offset-4 hover:underline dark:text-dragon-300">
        Natrag na početnu
      </Link>
    </section>
  );
}
