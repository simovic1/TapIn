import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState, type FormEvent } from 'react';
import { Link, useLocation } from 'react-router';
import { Alert } from '../../../components/ui/Alert';
import { Button } from '../../../components/ui/Button';
import { TextField } from '../../../components/ui/TextField';
import { apiFetch } from '../../../lib/api/client';
import { errorMessage, fieldErrors } from '../../../lib/api/errors';
import { sessionKeys, useCurrentUser } from '../../../lib/session/session';

export function PasswordRecoveryForm({ confirm = false }: { confirm?: boolean }) {
  const token = useLocation().hash.replace(/^#/, '');
  const csrf = useCurrentUser();
  const client = useQueryClient();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [confirmation, setConfirmation] = useState('');
  const [validation, setValidation] = useState('');
  const title = confirm ? 'Nova lozinka' : 'Zaboravljena lozinka';
  const reset = useMutation({
    mutationFn: () => apiFetch<void>(`/auth/password-reset/${confirm ? 'confirm' : 'request'}`, {
      method: 'POST', body: JSON.stringify(confirm ? { token, newPassword: password } : { email: email.trim() }),
    }),
    onSuccess: () => {
      setPassword(''); setConfirmation('');
      if (confirm) { client.clear(); client.setQueryData(sessionKeys.currentUser, null); }
    },
  });
  useEffect(() => { document.title = `${title} · TapIn`; }, [title]);
  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (reset.isPending) return;
    if (confirm) {
      if ([...password].length < 10) { setValidation('Lozinka mora imati najmanje 10 znakova.'); return; }
      if (new TextEncoder().encode(password).length > 72) { setValidation('Lozinka je predugačka (najviše 72 bajta).'); return; }
      if (password !== confirmation) { setValidation('Lozinke se ne podudaraju.'); return; }
    }
    setValidation(''); reset.mutate();
  };
  const errors = fieldErrors(reset.error);
  return (
    <form onSubmit={submit} className="flex flex-col gap-4" aria-labelledby="recovery-title">
      <h1 id="recovery-title" className="text-xl font-semibold">{title}</h1>
      {reset.isSuccess ? <Alert tone="success">{confirm
        ? 'Lozinka je promijenjena. Prijavite se novom lozinkom. Prethodne sesije više ne vrijede.'
        : 'Ako postoji aktivan račun s tom e-mail adresom, poslat ćemo poveznicu za promjenu lozinke. Provjerite i neželjenu poštu.'}</Alert>
      : confirm && !token ? <Alert tone="error">Poveznica nije potpuna. Zatražite novu poveznicu.</Alert> : <>
        <p>{confirm ? 'Odaberite novu lozinku od najmanje 10 znakova.' : 'Unesite e-mail adresu računa. Poveznica vrijedi 30 minuta.'}</p>
        {(validation || reset.isError) && <Alert tone="error">{validation || errorMessage(reset.error)}</Alert>}
        {csrf.isError && <Alert tone="error">{errorMessage(csrf.error)}</Alert>}
        {confirm ? <>
          <TextField label="Nova lozinka" name="new-password" type="password" autoComplete="new-password" required
            value={password} onChange={(event) => setPassword(event.target.value)} error={errors.newPassword} />
          <TextField label="Ponovite lozinku" name="confirm-password" type="password" autoComplete="new-password" required
            value={confirmation} onChange={(event) => setConfirmation(event.target.value)} />
        </> : <TextField label="E-mail" type="email" name="email" autoComplete="email" required
          value={email} onChange={(event) => setEmail(event.target.value)} error={errors.email} />}
        <Button type="submit" fullWidth loading={reset.isPending} disabled={csrf.isPending || csrf.isError}>
          {confirm ? 'Spremi lozinku' : 'Pošalji poveznicu'}
        </Button>
      </>}
      <Link to="/prijava" className="text-dragon-700 underline">Natrag na prijavu</Link>
      {confirm && !reset.isSuccess && <Link to="/zaboravljena-lozinka" className="text-dragon-700 underline">Zatraži novu poveznicu</Link>}
    </form>
  );
}
