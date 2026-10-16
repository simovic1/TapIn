import type { ReactNode } from 'react';
import { Icon } from './Icon';

/** Successful load with no results (distinct from loading and from errors). */
export function EmptyState({ title, description, action }: { title: string; description?: string; action?: ReactNode }) {
  return (
    <div className="flex flex-col items-center gap-3 rounded-2xl border border-dashed border-slate-300 px-4 py-10 text-center dark:border-slate-700">
      <Icon name="inbox" className="size-8 text-slate-400" />
      <div>
        <p className="font-semibold">{title}</p>
        {description && <p className="mt-1 text-sm text-slate-600 dark:text-slate-400">{description}</p>}
      </div>
      {action}
    </div>
  );
}
