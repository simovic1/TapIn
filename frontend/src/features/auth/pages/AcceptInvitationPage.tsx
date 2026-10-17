import { useQuery } from '@tanstack/react-query';
import { useEffect, useState, type FormEvent } from 'react';
import { Link, useLocation, useNavigate } from 'react-router';
import { Alert } from '../../../components/ui/Alert';
import { Button } from '../../../components/ui/Button';
import { LoadingState } from '../../../components/ui/LoadingState';
import { TextField } from '../../../components/ui/TextField';
import { apiFetch } from '../../../lib/api/client';
import { errorMessage, fieldErrors } from '../../../lib/api/errors';
import type { Schemas } from '../../../lib/api/types';
import { formatDateTime } from '../../../lib/format';
import { useAcceptInvitation, useCurrentUser } from '../../../lib/session/session';

type InvitationPreview = Schemas['InvitationPreview'];

const MIN_PASSWORD_LENGTH = 10;
const MAX_PASSWORD_BYTES = 72;

/**
 * Invitation link target: `/pozivnica#<token>`. The token lives in the URL fragment, which browsers never send to
 * the server, and is posted in the request body instead.
 */
export function AcceptInvitationPage() {
  const location = useLocation();
  const navigate = useNavigate();
  const token = location.hash.replace(/^#/, '');
  // Loading /me first also makes sure the CSRF cookie exists before the first POST.
  const currentUser = useCurrentUser();
  const accept = useAcceptInvitation();

  const preview = useQuery({
    queryKey: ['invitation', 'preview', token],
    queryFn: () =>
      apiFetch<InvitationPreview>('/auth/invitations/preview', { method: 'POST', body: JSON.stringify({ token }) }),
    enabled: token.length > 0 && !currentUser.isPending,
    retry: false,
    staleTime: Infinity,
    meta: { ignoreUnauthorized: true },
  });

  useEffect(() => {
    document.title = 'Aktivacija računa · TapIn';
  }, []);

  if (!token) {
    return (
      <InvalidInvitation message="Poveznica iz pozivnice nije potpuna. Otvorite je izravno iz e-maila." />
    );
  }
  if (currentUser.isPending || preview.isPending) return <LoadingState label="Provjera pozivnice…" />;
  if (preview.isError) {
    return <InvalidInvitation message={errorMessage(preview.error)} />;
  }

  return (
    <ActivationForm
      preview={preview.data}
      submitting={accept.isPending}
      error={accept.error}
      onSubmit={(values) =>
        accept.mutate({ token, ...values }, { onSuccess: () => navigate('/', { replace: true }) })
      }
    />
  );
}

function InvalidInvitation({ message }: { message: string }) {
  return (
    <div className="flex flex-col gap-4">
      <h1 className="text-xl font-semibold">Aktivacija računa</h1>
      <Alert tone="error">{message}</Alert>
      <Link to="/prijava" className="text-sm font-medium text-dragon-700 underline-offset-4 hover:underline dark:text-dragon-300">
        Već imate račun? Prijavite se
      </Link>
    </div>
  );
}

interface ActivationValues {
  firstName: string;
  lastName: string;
  phone: string | null;
  password: string;
}

function ActivationForm({
  preview,
  submitting,
  error,
  onSubmit,
}: {
  preview: InvitationPreview;
  submitting: boolean;
  error: unknown;
  onSubmit: (values: ActivationValues) => void;
}) {
  const [firstName, setFirstName] = useState(preview.firstName);
  const [lastName, setLastName] = useState(preview.lastName);
  const [phone, setPhone] = useState('');
  const [password, setPassword] = useState('');
  const [confirmation, setConfirmation] = useState('');
  const [localErrors, setLocalErrors] = useState<Record<string, string>>({});
  const serverErrors = fieldErrors(error);

  const handleSubmit = (event: FormEvent) => {
    event.preventDefault();
    if (submitting) return;
    const errors: Record<string, string> = {};
    if ([...password].length < MIN_PASSWORD_LENGTH) errors.password = `Najmanje ${MIN_PASSWORD_LENGTH} znakova.`;
    else if (new TextEncoder().encode(password).length > MAX_PASSWORD_BYTES) errors.password = 'Lozinka je predugačka.';
    if (confirmation !== password) errors.confirmation = 'Lozinke se ne podudaraju.';
    setLocalErrors(errors);
    if (Object.keys(errors).length > 0) return;
    onSubmit({ firstName: firstName.trim(), lastName: lastName.trim(), phone: phone.trim() || null, password });
  };

  return (
    <form onSubmit={handleSubmit} className="flex flex-col gap-4" aria-labelledby="activation-title">
      <div>
        <h1 id="activation-title" className="text-xl font-semibold">
          Aktivacija računa
        </h1>
        <p className="mt-1 text-sm text-slate-600 dark:text-slate-400">
          Pozvani ste u <strong>{preview.clubName}</strong> kao <strong className="break-all">{preview.email}</strong>.
          Pozivnica vrijedi do {formatDateTime(preview.expiresAt)}.
        </p>
      </div>

      {error != null && <Alert tone="error">{errorMessage(error)}</Alert>}

      <TextField label="Ime" name="given-name" autoComplete="given-name" required value={firstName}
        onChange={(e) => setFirstName(e.target.value)} error={serverErrors.firstName} />
      <TextField label="Prezime" name="family-name" autoComplete="family-name" required value={lastName}
        onChange={(e) => setLastName(e.target.value)} error={serverErrors.lastName} />
      <TextField label="Telefon" name="tel" type="tel" autoComplete="tel" hint="Neobavezno." value={phone}
        onChange={(e) => setPhone(e.target.value)} error={serverErrors.phone} />
      {/* Hidden username field lets password managers store the new credentials under the right e-mail. */}
      <input type="email" name="username" autoComplete="username" value={preview.email} readOnly hidden />
      <TextField label="Nova lozinka" name="new-password" type="password" autoComplete="new-password" required
        hint={`Najmanje ${MIN_PASSWORD_LENGTH} znakova.`} value={password}
        onChange={(e) => setPassword(e.target.value)} error={localErrors.password ?? serverErrors.password} />
      <TextField label="Ponovite lozinku" name="confirm-password" type="password" autoComplete="new-password" required
        value={confirmation} onChange={(e) => setConfirmation(e.target.value)} error={localErrors.confirmation} />

      <Button type="submit" fullWidth loading={submitting}>
        Aktiviraj račun
      </Button>
    </form>
  );
}
