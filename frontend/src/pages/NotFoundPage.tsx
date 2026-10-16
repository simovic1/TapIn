import { Link } from 'react-router';

export function NotFoundPage() {
  return (
    <section className="space-y-3">
      <h1 className="text-2xl font-semibold">Stranica ne postoji</h1>
      <Link to="/" className="text-blue-600 hover:underline dark:text-blue-400">
        Natrag na početnu
      </Link>
    </section>
  );
}
