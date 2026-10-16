import { Spinner } from './Spinner';

/** Loading indicator announced politely to screen readers. */
export function LoadingState({ label = 'Učitavanje…', fullPage = false }: { label?: string; fullPage?: boolean }) {
  return (
    <div
      role="status"
      aria-live="polite"
      className={`flex items-center justify-center gap-3 text-slate-600 dark:text-slate-300 ${
        fullPage ? 'min-h-dvh' : 'py-10'
      }`}
    >
      <Spinner />
      <span>{label}</span>
    </div>
  );
}
