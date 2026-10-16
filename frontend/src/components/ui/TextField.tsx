import { useId, type InputHTMLAttributes } from 'react';

export interface TextFieldProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'id'> {
  label: string;
  hint?: string;
  error?: string;
}

/**
 * Labelled input: the label is always visible and associated, hint and error are linked via aria-describedby,
 * and an error sets aria-invalid so screen readers announce it.
 */
export function TextField({ label, hint, error, required, className = '', ...rest }: TextFieldProps) {
  const id = useId();
  const hintId = hint ? `${id}-hint` : undefined;
  const errorId = error ? `${id}-error` : undefined;
  const describedBy = [hintId, errorId].filter(Boolean).join(' ') || undefined;

  return (
    <div className={`flex flex-col gap-1 ${className}`}>
      <label htmlFor={id} className="text-sm font-medium">
        {label}
        {required && (
          <span className="text-red-600 dark:text-red-400" aria-hidden="true">
            {' '}
            *
          </span>
        )}
      </label>
      <input
        id={id}
        required={required}
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy}
        className={`min-h-11 rounded-lg border bg-white px-3 text-base dark:bg-slate-950 ${
          error ? 'border-red-600 dark:border-red-400' : 'border-slate-300 dark:border-slate-700'
        }`}
        {...rest}
      />
      {hint && (
        <p id={hintId} className="text-sm text-slate-500 dark:text-slate-400">
          {hint}
        </p>
      )}
      {error && (
        <p id={errorId} className="text-sm text-red-700 dark:text-red-400">
          {error}
        </p>
      )}
    </div>
  );
}
