import { errorMessage } from '../../lib/api/errors';
import { Button } from './Button';
import { Icon } from './Icon';

interface ErrorStateProps {
  error: unknown;
  title?: string;
  onRetry?: () => void;
  retrying?: boolean;
}

/** Failed load: explains what went wrong and offers a retry; never pretends the data is there. */
export function ErrorState({ error, title = 'Podaci se nisu mogli učitati', onRetry, retrying }: ErrorStateProps) {
  return (
    <div
      role="alert"
      className="flex flex-col items-center gap-3 rounded-2xl border border-red-200 bg-red-50 px-4 py-8 text-center dark:border-red-900 dark:bg-red-950/40"
    >
      <Icon name="alert" className="size-8 text-red-600 dark:text-red-400" />
      <div>
        <p className="font-semibold">{title}</p>
        <p className="mt-1 text-sm text-slate-700 dark:text-slate-300">{errorMessage(error)}</p>
      </div>
      {onRetry && (
        <Button variant="secondary" onClick={onRetry} loading={retrying}>
          <Icon name="refresh" className="size-4" />
          Pokušaj ponovno
        </Button>
      )}
    </div>
  );
}
