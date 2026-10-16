import { useState } from 'react';
import { Link, useParams } from 'react-router';
import { Alert } from '../../../components/ui/Alert';
import { Button } from '../../../components/ui/Button';
import { PageHeader } from '../../../components/ui/PageHeader';
import { QueryState } from '../../../components/ui/QueryState';
import { formatDateTime } from '../../../lib/format';
import {
  BELT_LABELS,
  correctBeltPeriod,
  createBeltPeriod,
  useBeltAudit,
  useBeltHistory,
  useBeltSaved,
  useMemberName,
  type BeltAuditEntry,
  type BeltHistory,
  type BeltPeriod,
  type CurrentBelt,
} from '../api';
import { BeltForm, emptyValues, valuesOf, type BeltFormValues } from '../components/BeltForm';
import { awardedLabel, BeltHistoryView, shortDate } from '../components/BeltHistoryView';

/** Trainer/admin belt management for one member (spec §3.3, §12.2): entry, promotion, correction, audit. */
export function MemberBeltsPage() {
  const membershipId = Number(useParams().membershipId);
  const member = useMemberName(membershipId);
  const history = useBeltHistory(membershipId);
  const name = member.data ? `${member.data.firstName} ${member.data.lastName}` : 'Član';

  return (
    <section className="max-w-3xl">
      <p className="mb-2 text-sm">
        <Link to={`/clanovi/${membershipId}`} className="underline underline-offset-2">
          ← Natrag na profil člana
        </Link>
      </p>
      <PageHeader title={`Pojasevi: ${name}`} description="Datum dodjele i početak brojanja treninga vode se odvojeno." />
      <QueryState query={history} loadingLabel="Učitavanje pojaseva…">
        {(data) => <Management membershipId={membershipId} history={data} onReload={() => history.refetch()} />}
      </QueryState>
    </section>
  );
}

function toRequest(v: BeltFormValues) {
  return v.awardedKnown
    ? { belt: v.belt, awardedOn: v.awardedOn, promotionTiming: v.timing, note: v.note.trim() || null }
    : { belt: v.belt, awardedOn: null, promotionTiming: v.timing, effectiveFromDate: v.effectiveFromDate, note: v.note.trim() || null };
}

function Management({ membershipId, history, onReload }: { membershipId: number; history: BeltHistory; onReload: () => void }) {
  const saved = useBeltSaved(membershipId);
  const [adding, setAdding] = useState(false);
  const [editing, setEditing] = useState<{ period: BeltPeriod; initial: BeltFormValues; key: number }>();
  const [notice, setNotice] = useState<{ tone: 'success' | 'warning'; text: string }>();

  const open = (period: BeltPeriod) => {
    setNotice(undefined);
    setAdding(false);
    setEditing((e) => ({ period, initial: valuesOf(period), key: (e?.key ?? 0) + 1 }));
  };

  const nextBelt = (current: CurrentBelt) =>
    current === 'UNKNOWN' ? 'Početni unos pojasa' : current === 'BLACK' ? 'Unos pojasa' : 'Nova promocija';

  return (
    <div className="flex flex-col gap-6">
      {notice && <Alert tone={notice.tone}>{notice.text}</Alert>}
      <BeltHistoryView
        history={history}
        actions={(p) =>
          editing?.period.beltPeriodId === p.beltPeriodId ? null : (
            <div className="mt-2">
              <Button variant="secondary" onClick={() => open(p)} aria-label={`Ispravi ${BELT_LABELS[p.belt]} pojas`}>
                Ispravi
              </Button>
            </div>
          )
        }
      />

      {editing && (
        <BeltForm
          key={editing.key}
          title={`Ispravak: ${BELT_LABELS[editing.period.belt]} pojas`}
          initial={editing.initial}
          correction
          submitLabel="Spremi ispravak"
          onSubmit={(v) =>
            correctBeltPeriod(membershipId, editing.period.beltPeriodId, {
              expectedVersion: editing.period.version,
              reason: v.reason.trim(),
              ...toRequest(v),
            })
          }
          onDone={(result) => {
            saved(result);
            setEditing(undefined);
            setNotice({ tone: 'success', text: 'Ispravak je spremljen; treninzi su ponovno raspoređeni.' });
          }}
          onConflict={(fresh) => {
            onReload();
            if (fresh) setEditing((e) => ({ period: fresh, initial: valuesOf(fresh), key: (e?.key ?? 0) + 1 }));
            else setEditing(undefined);
            setNotice({
              tone: 'warning',
              text: 'Zapis je u međuvremenu izmijenio netko drugi. Prikazano je svježe stanje – provjerite ga i ponovno odlučite.',
            });
          }}
          onCancel={() => setEditing(undefined)}
        />
      )}

      {adding ? (
        <BeltForm
          title={nextBelt(history.currentBelt)}
          initial={emptyValues()}
          correction={false}
          submitLabel="Spremi pojas"
          onSubmit={(v) => createBeltPeriod(membershipId, toRequest(v))}
          onDone={(result) => {
            saved(result);
            setAdding(false);
            setNotice({ tone: 'success', text: `${BELT_LABELS[result.beltPeriod.belt]} pojas je spremljen.` });
          }}
          onCancel={() => setAdding(false)}
        />
      ) : (
        <div>
          <Button onClick={() => { setNotice(undefined); setEditing(undefined); setAdding(true); }}>
            {nextBelt(history.currentBelt)}
          </Button>
          <p className="mt-2 text-sm text-slate-600 dark:text-slate-400">
            Raniji pojas bez poznatog datuma također se unosi ovdje. Pojas se ne briše – pogrešan unos ispravite.
          </p>
        </div>
      )}

      <AuditList membershipId={membershipId} />
    </div>
  );
}

const describe = (values: Record<string, unknown> | null) =>
  values
    ? `${BELT_LABELS[(values.belt as CurrentBelt) ?? 'UNKNOWN']}, dodijeljen ${awardedLabel((values.awardedOn as string | null) ?? null)}, broji se od ${shortDate(values.effectiveFromDate as string)}`
    : '–';

function AuditList({ membershipId }: { membershipId: number }) {
  const audit = useBeltAudit(membershipId);
  return (
    <section aria-labelledby="belt-audit" className="flex flex-col gap-2">
      <h2 id="belt-audit" className="text-lg font-semibold">
        Povijest ispravaka
      </h2>
      <QueryState query={audit} isEmpty={(items) => items.length === 0} empty={{ title: 'Još nema unosa' }}>
        {(items: BeltAuditEntry[]) => (
          <ol className="flex flex-col gap-2 text-sm">
            {items.map((e) => (
              <li key={e.auditId} className="rounded-xl border border-slate-200 p-3 dark:border-slate-800">
                <p className="font-medium">
                  {e.action === 'CREATED' ? 'Unos' : 'Ispravak'} · {formatDateTime(e.occurredAt)}
                </p>
                {e.action === 'CORRECTED' && <p>Prije: {describe(e.oldValues)}</p>}
                <p>{e.action === 'CORRECTED' ? 'Poslije' : 'Uneseno'}: {describe(e.newValues)}</p>
                {e.reason && <p>Razlog: {e.reason}</p>}
              </li>
            ))}
          </ol>
        )}
      </QueryState>
    </section>
  );
}
