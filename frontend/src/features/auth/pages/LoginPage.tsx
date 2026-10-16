import { useEffect, useState, type FormEvent } from 'react';
import { Link, Navigate, useLocation, useNavigate } from 'react-router';
import { Alert } from '../../../components/ui/Alert';
import { Button } from '../../../components/ui/Button';
import { TextField } from '../../../components/ui/TextField';
import { errorMessage, fieldErrors } from '../../../lib/api/errors';
import type { LoginRedirectState } from '../../../lib/session/guards';
import { useCurrentUser, useLogin } from '../../../lib/session/session';
import { useSessionExpired } from '../../../lib/session/sessionExpiry';

/** Only same-app relative paths are accepted as a post-login target (no open redirects). */
function safeTarget(from: string | undefined): string {
  return from && from.startsWith('/') && !from.startsWith('//') ? from : '/';
}

export function LoginPage() {
  const { data: user } = useCurrentUser();
  const login = useLogin();
  const navigate = useNavigate();
  const location = useLocation();
  const expired = useSessionExpired();
  const target = safeTarget((location.state as LoginRedirectState | null)?.from);
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');

  useEffect(() => {
    document.title = 'Prijava · TapIn';
  }, []);

  if (user && !login.isPending) return <Navigate to={target} replace />;

  const errors = fieldErrors(login.error);

  const onSubmit = (event: FormEvent) => {
    event.preventDefault();
    login.mutate({ email: email.trim(), password }, { onSuccess: () => navigate(target, { replace: true }) });
  };

  return (
    <form onSubmit={onSubmit} noValidate={false} className="flex flex-col gap-4" aria-labelledby="login-title">
      <h1 id="login-title" className="text-xl font-semibold">
        Prijava
      </h1>

      {expired && !login.isError && (
        <Alert tone="warning" title="Sesija je istekla">
          Prijavite se ponovno za nastavak rada.
        </Alert>
      )}
      {login.isError && <Alert tone="error">{errorMessage(login.error)}</Alert>}

      <TextField
        label="E-mail"
        type="email"
        name="email"
        autoComplete="username"
        inputMode="email"
        required
        value={email}
        onChange={(e) => setEmail(e.target.value)}
        error={errors.email}
      />
      <TextField
        label="Lozinka"
        type="password"
        name="password"
        autoComplete="current-password"
        required
        value={password}
        onChange={(e) => setPassword(e.target.value)}
        error={errors.password}
      />

      <Button type="submit" fullWidth loading={login.isPending}>
        Prijavi se
      </Button>

      <Link
        to="/zaboravljena-lozinka"
        className="self-center rounded text-sm font-medium text-blue-700 underline-offset-4 hover:underline dark:text-blue-400"
      >
        Zaboravljena lozinka?
      </Link>
    </form>
  );
}
