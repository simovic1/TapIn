import { useState } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import { Link } from 'react-router';
import { apiFetch } from '../../../lib/api/client';
import type { Schemas } from '../../../lib/api/types';
import { clubToday, addDays } from '../../../lib/format';
import { errorMessage } from '../../../lib/api/errors';
import { PageHeader } from '../../../components/ui/PageHeader';
import { TextField } from '../../../components/ui/TextField';
import { Button } from '../../../components/ui/Button';
import { Alert } from '../../../components/ui/Alert';
import { QueryState } from '../../../components/ui/QueryState';

const decimal = (n: number | null) => n === null ? 'Nema nazivnika' : new Intl.NumberFormat('hr-HR', { maximumFractionDigits: 2 }).format(n);
const belts = ['UNKNOWN','WHITE','BLUE','PURPLE','BROWN','BLACK'];
const beltLabels: Record<string,string> = { UNKNOWN:'Nepoznat',WHITE:'Bijeli',BLUE:'Plavi',PURPLE:'Ljubičasti',BROWN:'Smeđi',BLACK:'Crni' };
const selectClass = 'min-h-11 rounded border p-2 dark:bg-slate-900';
export function ReportsPage() {
  const today=clubToday();
  const [from,setFrom]=useState(today.slice(0,8)+'01');
  const [to,setTo]=useState(addDays(today,1));
  const [belt,setBelt]=useState('');
  const [member,setMember]=useState('');
  const [search,setSearch]=useState('');
  const [sort,setSort]=useState('presentCount,desc');
  const [page,setPage]=useState(0);
  const valid=!!from && !!to && from<to && (Date.parse(to)-Date.parse(from))/86400000<=366;
  const params=new URLSearchParams({from,to,sort});
  if(belt) params.set('belt',belt);
  if(member) params.set('membershipId',member);
  const filters=params.toString();
  const members=useQuery({queryKey:['reports','member-search',search],queryFn:()=>apiFetch<Schemas['MemberSummaryPage']>(`/members?q=${encodeURIComponent(search)}&size=100`),enabled:search.trim().length>=2});
  const report=useQuery({queryKey:['statistics','reports',filters,page],queryFn:()=>apiFetch<Schemas['AttendanceReport']>(`/reports/attendance?${filters}&page=${page}&size=20`),enabled:valid});
  const download=useMutation({mutationFn:async (query:string)=>{
    const blob=await apiFetch<Blob>(`/reports/attendance.csv?${query}`,{},'blob');
    const url=URL.createObjectURL(blob);
    const link=document.createElement('a');link.href=url;link.download='dolasci.csv';document.body.appendChild(link);link.click();link.remove();
    setTimeout(()=>URL.revokeObjectURL(url),1000);
  }});
  const change=(set:(v:string)=>void)=>(value:string)=>{set(value);setPage(0);download.reset();};
  return <section className="space-y-5">
    <PageHeader title="Klupski izvještaji" description="Dolasci na održanim treninzima, prema datumu treninga u Europe/Zagreb." />
    <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
      <TextField label="Od (uključivo)" type="date" value={from} onChange={e=>change(setFrom)(e.target.value)} />
      <TextField label="Do (isključivo)" type="date" value={to} onChange={e=>change(setTo)(e.target.value)} />
      <label className="flex flex-col">Trenutačni pojas<select className={selectClass} value={belt} onChange={e=>change(setBelt)(e.target.value)}><option value="">Svi pojasevi</option>{belts.map(b=><option key={b} value={b}>{beltLabels[b]}</option>)}</select></label>
      <label className="flex flex-col">Redoslijed<select className={selectClass} value={sort} onChange={e=>change(setSort)(e.target.value)}>
        <option value="presentCount,desc">Najviše dolazaka</option><option value="presentCount,asc">Najmanje dolazaka</option><option value="lastName,asc">Prezime A–Ž</option><option value="noShowRate,desc">Najviše nedolazaka (%)</option><option value="lastPresentOn,asc">Najstariji dolazak</option>
      </select></label>
      <TextField label="Pronađi člana" value={search} onChange={e=>setSearch(e.target.value)} hint="Unesite barem dva znaka pa odaberite člana." />
      <label className="flex flex-col">Član<select className={selectClass} value={member} onChange={e=>change(setMember)(e.target.value)}><option value="">Svi članovi</option>
        {member && !members.data?.items.some(m=>String(m.membershipId)===member) && <option value={member}>Odabrani član #{member}</option>}
        {members.data?.items.map(m=><option key={m.membershipId} value={m.membershipId}>{m.firstName} {m.lastName}</option>)}
      </select></label>
    </div>
    {members.isError && <Alert tone="error">Pretraga članova nije uspjela. {errorMessage(members.error)}</Alert>}
    <p className="text-sm">Pojas odabire članove prema trenutačnom pojasu, uključujući njihove ranije dolaske. Brojači mjeseca i 7/30 dana presjek su odabranog razdoblja s tim prozorima. Zadnji dolazak je iz cijele povijesti.</p>
    {!valid && <Alert tone="error">Odaberite razdoblje od 1 do 366 dana.</Alert>}
    <Button disabled={!valid || report.isFetching || !report.isSuccess} loading={download.isPending} onClick={()=>download.mutate(filters)}>Preuzmi CSV (svi rezultati)</Button>
    {download.isError && <Alert tone="error">Izvoz nije uspio. {errorMessage(download.error)}</Alert>}
    {valid && <QueryState query={report}>{data=><>
      <p>Broj rezultata: {data.page.totalElements}. Prosjek: {decimal(data.totals.averagePresentPerSession)} dolazaka po terminu ({data.totals.totalPresent} dolazaka / {data.totals.heldSessions} održanih termina, uključujući termine bez dolazaka).</p>
      <p>Nedolasci = prijavljeni ABSENT / prijavljeni s PRESENT ili ABSENT. Nepotvrđeni UNMARKED nisu u nazivniku.</p>
      {data.items.length===0 ? <p>Nema članova za odabrane filtre.</p> : <div className="overflow-x-auto"><table className="w-full text-left text-sm"><caption className="sr-only">Dolasci članova</caption>
        <thead><tr>{['Član','Pojas','Dolasci','Ovaj mjesec','7 dana','30 dana','Nedolasci / evidentirane prijave','Nepotvrđene prijave','Zadnji dolazak'].map(h=><th className="p-2" key={h}>{h}</th>)}</tr></thead>
        <tbody>{data.items.map(r=><tr className="border-t" key={r.membershipId}><td className="p-2"><Link className="underline" to={`/clanovi/${r.membershipId}`}>{r.firstName} {r.lastName}</Link></td><td>{beltLabels[r.currentBelt]}</td><td>{r.presentCount}</td><td>{r.presentThisMonth}</td><td>{r.presentLast7Days}</td><td>{r.presentLast30Days}</td><td>{r.registeredButAbsent} / {r.decidedRegistrations} ({r.noShowRate===null?'Nema nazivnika':decimal(r.noShowRate*100)+' %'})</td><td>{r.unmarkedRegistrations}</td><td>{r.lastPresentOn ?? 'Nikad'}</td></tr>)}</tbody>
      </table></div>}
      <div className="flex gap-3 items-center"><Button disabled={page===0} onClick={()=>setPage(page-1)}>Prethodna</Button><span>Stranica {page+1} / {Math.max(1,data.page.totalPages)}</span><Button disabled={page+1>=data.page.totalPages} onClick={()=>setPage(page+1)}>Sljedeća</Button></div>
      <h2 className="text-lg font-semibold">Po mjesecima</h2>
      <ul>{data.totals.monthly.map(m=><li key={m.month}>{m.month}: {m.totalPresent} dolazaka / {m.heldSessions} termina = {decimal(m.averagePresentPerSession)}</li>)}</ul>
      <details><summary className="cursor-pointer min-h-11">Dolasci po održanom terminu</summary><ul>{data.totals.sessions.map(s=><li key={s.trainingId}><Link className="underline" to={`/treninzi/${s.trainingId}`}>{s.localDate} · termin #{s.trainingId}</Link>: {s.totalPresent} dolazaka</li>)}</ul></details>
    </>}</QueryState>}
    <InactiveMembers />
  </section>;
}
function InactiveMembers() {
  const [days,setDays]=useState('14');const [page,setPage]=useState(0);
  const query=useQuery({queryKey:['statistics','inactive',days,page],queryFn:()=>apiFetch<Schemas['InactiveMemberPage']>(`/reports/inactive-members?days=${days}&page=${page}&size=20`)});
  return <section className="space-y-3 border-t pt-5"><h2 className="text-lg font-semibold">Dulje odsutni članovi</h2>
    <p>Aktivna članstva prema cijeloj povijesti do danas; ovaj pregled je neovisan o filtrima izvještaja iznad. Za člana bez dolazaka prag se računa od učlanjenja.</p>
    <label>Prag odsutnosti <select className={selectClass} value={days} onChange={e=>{setDays(e.target.value);setPage(0);}}><option value="14">14 dana</option><option value="30">30 dana</option></select></label>
    <QueryState query={query}>{data=><><p>Odsutnih članova: {data.page.totalElements}</p>{!data.items.length && <p>Nema dulje odsutnih članova.</p>}<ul>{data.items.map(m=><li className="py-2" key={m.membershipId}><Link className="underline" to={`/clanovi/${m.membershipId}`}>{m.firstName} {m.lastName}</Link> · {m.lastPresentOn===null?'Nikad nije dolazio':`${m.daysSinceLastPresent} dana; zadnji dolazak ${m.lastPresentOn}`}</li>)}</ul>
    <Button disabled={page===0} onClick={()=>setPage(page-1)}>Prethodna stranica odsutnih</Button><Button disabled={page+1>=data.page.totalPages} onClick={()=>setPage(page+1)}>Sljedeća stranica odsutnih</Button></>}</QueryState>
  </section>;
}
