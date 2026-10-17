import { useEffect, useRef, useState } from 'react';

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { Link } from 'react-router';

import { apiFetch } from '../../lib/api/client';

import { useCurrentUser } from '../../lib/session/session';

import { Icon } from '../ui/Icon';



type Notification = { id: number; title: string; body: string; url: string; createdAt: string; read: boolean };

type Inbox = { items: Notification[]; unreadCount: number };



export function NotificationBell() {

  const { data: user } = useCurrentUser();

  const client = useQueryClient();

  const [open, setOpen] = useState(false);

  const [pushEnabled, setPushEnabled] = useState(false);

  const panel = useRef<HTMLDivElement>(null);

  const button = useRef<HTMLButtonElement>(null);

  const key = ['notifications', user?.userId];

  const supported = 'serviceWorker' in navigator && 'PushManager' in window && 'Notification' in window;

  const inbox = useQuery({ queryKey: key, queryFn: () => apiFetch<Inbox>('/me/notifications'), enabled: !!user, refetchInterval: 30_000 });

  const read = useMutation({

    mutationFn: (id: number) => apiFetch<void>(`/me/notifications/${id}/read`, { method: 'PUT' }),

    onSuccess: () => client.invalidateQueries({ queryKey: key }),

  });

  useEffect(() => {

    if (!supported) return;

    let active = true;

    void navigator.serviceWorker.getRegistration().then(async (registration) => {

      const subscription = await registration?.pushManager.getSubscription();

      if (subscription && active) {

        await apiFetch('/me/push-subscription', { method: 'PUT', body: JSON.stringify(subscription.toJSON()) });

      }

      if (active) setPushEnabled(!!subscription);

    }).catch(() => {});

    const refresh = () => { void client.invalidateQueries({ queryKey: ['notifications'] }); };

    navigator.serviceWorker.addEventListener('message', refresh);

    return () => { active = false; navigator.serviceWorker.removeEventListener('message', refresh); };

  }, [supported, client, user?.userId]);

  useEffect(() => {

    if (!open) return;

    const outside = (event: PointerEvent) => { if (!panel.current?.contains(event.target as Node)) setOpen(false); };

    const escape = (event: KeyboardEvent) => { if (event.key === 'Escape') { setOpen(false); button.current?.focus(); } };

    document.addEventListener('pointerdown', outside);

    document.addEventListener('keydown', escape);

    return () => { document.removeEventListener('pointerdown', outside); document.removeEventListener('keydown', escape); };

  }, [open]);

  const push = useMutation({

    mutationFn: async () => {

      if (pushEnabled) {

        const registration = await navigator.serviceWorker.getRegistration();

        const subscription = await registration?.pushManager.getSubscription();

        if (subscription) {

          await apiFetch('/me/push-subscription', { method: 'DELETE', body: JSON.stringify({ endpoint: subscription.endpoint }) });

          await subscription.unsubscribe();

        }

        setPushEnabled(false);

        return;

      }

      if (!user?.webPushPublicKey) throw new Error('Push obavijesti još nisu konfigurirane na poslužitelju.');

      if (await window.Notification.requestPermission() !== 'granted') throw new Error('Dopustite obavijesti u postavkama preglednika.');

      await navigator.serviceWorker.register('/sw.js', { updateViaCache: 'none' });

      const registration = await navigator.serviceWorker.ready;

      const encoded = user.webPushPublicKey.replace(/-/g, '+').replace(/_/g, '/');

      const applicationServerKey = Uint8Array.from(atob(encoded), c => c.charCodeAt(0));

      const subscription = await registration.pushManager.getSubscription() ?? await registration.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey });

      try {

        await apiFetch('/me/push-subscription', { method: 'PUT', body: JSON.stringify(subscription.toJSON()) });

      } catch (error) {

        await subscription.unsubscribe();

        throw error;

      }

      setPushEnabled(true);

    },

  });

  const unread = inbox.data?.unreadCount ?? 0;

  return <div ref={panel} className="relative">

    <button ref={button} type="button" aria-label={`Obavijesti${unread ? `, ${unread} nepročitanih` : ''}`} aria-expanded={open} aria-controls="notification-panel" onClick={() => setOpen(!open)} className="relative flex size-11 shrink-0 items-center justify-center rounded-lg hover:bg-white/10">

      <Icon name="bell" />

      {unread > 0 && <span className="absolute top-0 right-0 rounded-full bg-dragon-600 px-1.5 text-xs font-bold">{unread > 99 ? '99+' : unread}</span>}

    </button>

    {open && <section id="notification-panel" aria-label="Obavijesti" className="fixed top-14 right-2 left-2 max-h-[75dvh] overflow-y-auto rounded-xl border border-slate-200 bg-white p-4 text-slate-900 shadow-xl md:absolute md:top-full md:right-0 md:left-auto md:w-96 dark:border-slate-700 dark:bg-slate-900 dark:text-white">

      <div className="flex items-center justify-between"><h2 className="text-lg font-bold">Obavijesti</h2><button type="button" aria-label="Zatvori obavijesti" className="p-2" onClick={() => { setOpen(false); button.current?.focus(); }}><Icon name="close" /></button></div>

      <p className="mb-3 text-sm text-slate-500">Obavijesti o treninzima stižu ovdje i putem pusha.</p>

      {supported && (user?.webPushPublicKey || pushEnabled) ? <button type="button" disabled={push.isPending} onClick={() => push.mutate()} className="mb-3 min-h-11 rounded-lg bg-slate-100 px-3 text-sm font-semibold text-slate-900 disabled:opacity-50">{push.isPending ? 'Spremanje…' : pushEnabled ? 'Isključi push na ovom uređaju' : 'Uključi push na ovom uređaju'}</button>

        : <p className="mb-3 text-sm text-slate-500">{supported ? 'Push još nije konfiguriran. Obavijesti su dostupne ovdje.' : 'Push nije dostupan u ovom pregledniku. Na iPhoneu dodajte aplikaciju na početni zaslon.'}</p>}

      {push.isError && <p role="alert" className="mb-3 text-sm text-red-600">{push.error.message}</p>}

      {read.isError && <p role="alert">Označavanje pročitane obavijesti nije uspjelo.</p>}

      {inbox.isPending && <p role="status">Učitavanje obavijesti…</p>}

      {inbox.isError && <button type="button" onClick={() => void inbox.refetch()}>Obavijesti nisu učitane. Pokušaj ponovno.</button>}

      {inbox.data?.items.length === 0 && <p className="py-6 text-center text-slate-500">Još nema obavijesti.</p>}

      <ul className="divide-y divide-slate-200 dark:divide-slate-700">{inbox.data?.items.map(item => <li key={item.id} className={`py-3 ${item.read ? '' : 'border-l-2 border-dragon-500 pl-3'}`}>

        <Link to={item.url} onClick={() => { read.mutate(item.id); setOpen(false); }} className="block rounded focus-visible:outline-2">

          <p className={item.read ? 'font-medium' : 'font-bold'}>{item.title}</p>

          <p className="mt-1 text-sm">{item.body}</p>

          <time dateTime={item.createdAt} className="mt-1 block text-xs text-slate-500">{new Date(item.createdAt).toLocaleString('hr-HR')}</time>

        </Link>

        {!item.read && <button type="button" disabled={read.isPending} onClick={() => read.mutate(item.id)} className="mt-2 min-h-9 text-sm underline">Označi pročitano</button>}

      </li>)}</ul>

      {!!inbox.data?.items.length && <p className="mt-3 text-xs text-slate-500">Prikaz posljednjih 50 obavijesti.</p>}

    </section>}

  </div>;

}

