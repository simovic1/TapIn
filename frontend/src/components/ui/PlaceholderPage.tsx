interface PlaceholderPageProps {
  title: string;
  phase: string;
  description: string;
}

/** Temporary page for features that are scheduled in a later backlog phase (spec §16). */
export function PlaceholderPage({ title, phase, description }: PlaceholderPageProps) {
  return (
    <section className="space-y-3">
      <h1 className="text-2xl font-semibold">{title}</h1>
      <p className="text-slate-600 dark:text-slate-400">{description}</p>
      <p className="inline-block rounded-full border border-slate-300 px-3 py-1 text-xs text-slate-500 dark:border-slate-700">
        U izradi · {phase}
      </p>
    </section>
  );
}
