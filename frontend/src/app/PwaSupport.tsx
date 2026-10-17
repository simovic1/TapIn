import { useEffect, useState } from 'react';

interface InstallEvent extends Event {
  prompt(): Promise<void>;
  userChoice: Promise<{ outcome: 'accepted' | 'dismissed' }>;
}

export function PwaSupport() {
  const [install, setInstall] = useState<InstallEvent | null>(null);
  const [offline, setOffline] = useState(!navigator.onLine);
  const [dismissed, setDismissed] = useState(false);
  const [installed, setInstalled] = useState(false);
  const [error, setError] = useState(false);
  const standalone = window.matchMedia?.('(display-mode: standalone)').matches
    || (navigator as Navigator & { standalone?: boolean }).standalone;
  const ios = /iPad|iPhone|iPod/.test(navigator.userAgent)
    || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);

  useEffect(() => {
    const onInstall = (event: Event) => {
      event.preventDefault();
      setInstall(event as InstallEvent);
      setDismissed(false);
    };
    const onInstalled = () => { setInstall(null); setInstalled(true); };
    const online = () => setOffline(false);
    const offline = () => setOffline(true);
    window.addEventListener('beforeinstallprompt', onInstall);
    window.addEventListener('appinstalled', onInstalled);
    window.addEventListener('online', online);
    window.addEventListener('offline', offline);
    return () => {
      window.removeEventListener('beforeinstallprompt', onInstall);
      window.removeEventListener('appinstalled', onInstalled);
      window.removeEventListener('online', online);
      window.removeEventListener('offline', offline);
    };
  }, []);

  async function installApp() {
    if (!install) return;
    setError(false);
    try {
      await install.prompt();
      await install.userChoice;
    } catch {
      setError(true);
    } finally {
      setInstall(null);
    }
  }

  return (
    <>
      {offline && <div role="status" className="bg-amber-100 px-4 py-3 text-center text-amber-950">Nema internetske veze. Spremanje promjena zahtijeva vezu s poslužiteljem.</div>}
      {!standalone && !installed && !dismissed && (install || ios || error) && (
        <aside aria-label="Instalacija aplikacije" className="flex flex-wrap items-center justify-center gap-3 bg-slate-900 px-4 py-3 text-sm text-white">
          {install ? <button type="button" className="rounded bg-dragon-600 px-4 py-2 font-semibold" onClick={() => void installApp()}>Instaliraj TapIn</button>
            : <p>{error ? 'Instalacija nije uspjela. Pokušaj kroz izbornik preglednika.' : 'Za instalaciju na iPhone ili iPad otvori TapIn u Safariju, odaberi Dijeli pa Dodaj na početni zaslon.'}</p>}
          <button type="button" className="px-3 py-2 underline" onClick={() => setDismissed(true)}>Zatvori</button>
        </aside>
      )}
    </>
  );
}
