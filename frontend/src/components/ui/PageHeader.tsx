import { useEffect, type ReactNode } from 'react';

/** Page title (the only h1 on the page) that also sets the browser tab title. */
export function PageHeader({ title, description, actions }: { title: string; description?: string; actions?: ReactNode }) {
  useEffect(() => {
    document.title = `${title} · TapIn`;
  }, [title]);

  return (
    <div className="mb-6 flex flex-wrap items-end justify-between gap-3">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight">{title}</h1>
        {description && <p className="mt-1 text-slate-600 dark:text-slate-400">{description}</p>}
      </div>
      {actions && <div className="flex flex-wrap gap-2">{actions}</div>}
    </div>
  );
}
