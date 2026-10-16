import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, expect, it, vi } from 'vitest';
import { json, makeUser, mockApi, problem, renderApp } from '../../test/utils';
const report={from:'2026-10-01',to:'2026-10-17',totals:{heldSessions:2,totalPresent:1,averagePresentPerSession:0.5,monthly:[{month:'2026-10',heldSessions:2,totalPresent:1,averagePresentPerSession:0.5}],sessions:[]},items:[{membershipId:1,userId:11,firstName:'Ana',lastName:'Horvat',currentBelt:'BLUE',membershipStatus:'ACTIVE',email:'ana@example.com',presentCount:1,presentThisMonth:1,presentLast7Days:1,presentLast30Days:1,registeredButAbsent:1,decidedRegistrations:2,unmarkedRegistrations:1,noShowRate:0.5,lastPresentOn:'2026-10-15'}],page:{page:0,size:20,totalElements:1,totalPages:1}};
const empty={items:[],page:{page:0,size:20,totalElements:0,totalPages:0}};
afterEach(()=>vi.restoreAllMocks());
it('refreshes filters and exports the same selection, with visible denominators and member links',async()=>{
  const fetch=mockApi({
    'GET /api/v1/me':()=>json(200,makeUser(['TRAINER'])),
    'GET /api/v1/reports/attendance':()=>json(200,report),
    'GET /api/v1/reports/inactive-members':()=>json(200,empty),
    'GET /api/v1/reports/attendance.csv':()=>new Response('\uFEFFPrezime;Ime\r\nHorvat;Ana',{status:200,headers:{'Content-Type':'text/csv'}}),
  });
  Object.defineProperty(URL,'createObjectURL',{configurable:true,value:vi.fn(()=> 'blob:csv')});
  Object.defineProperty(URL,'revokeObjectURL',{configurable:true,value:vi.fn()});
  vi.spyOn(HTMLAnchorElement.prototype,'click').mockImplementation(()=>{});
  renderApp('/izvjestaji'); const user=userEvent.setup();
  expect(await screen.findByRole('link',{name:'Ana Horvat'})).toHaveAttribute('href','/clanovi/1');
  expect(screen.getByText('1 / 2 (50 %)')).toBeInTheDocument();
  await user.selectOptions(screen.getByLabelText('Trenutačni pojas'),'BLUE');
  await waitFor(()=>expect(fetch.mock.calls.some(([url])=>String(url).includes('belt=BLUE'))).toBe(true));
  await waitFor(()=>expect(screen.getByRole('button',{name:'Preuzmi CSV (svi rezultati)'})).toBeEnabled());
  await user.click(screen.getByRole('button',{name:'Preuzmi CSV (svi rezultati)'}));
  await waitFor(()=>expect(URL.createObjectURL).toHaveBeenCalled());
  const exported=fetch.mock.calls.find(([url])=>String(url).includes('attendance.csv'))!;
  expect(String(exported[0])).toContain('belt=BLUE');
  expect(String(exported[0])).not.toContain('page=');
  await user.selectOptions(screen.getByLabelText('Prag odsutnosti'),'30');
  await waitFor(()=>expect(fetch.mock.calls.some(([url])=>String(url).includes('inactive-members?days=30'))).toBe(true));
});
it('denies the reports screen to members',async()=>{
  const fetch=mockApi({'GET /api/v1/me':()=>json(200,makeUser(['MEMBER']))});
  renderApp('/izvjestaji');
  await screen.findByText(/Nemate pristup/);
  expect(fetch.mock.calls.some(([url])=>String(url).includes('/reports/'))).toBe(false);
});
it('shows an empty report and export errors without pretending a download succeeded',async()=>{
  mockApi({'GET /api/v1/me':()=>json(200,makeUser(['ADMIN'])),
    'GET /api/v1/reports/attendance':()=>json(200,{...report,...empty}),
    'GET /api/v1/reports/inactive-members':()=>json(200,empty),
    'GET /api/v1/reports/attendance.csv':()=>problem(500,'INTERNAL_ERROR'),
  });
  renderApp('/izvjestaji');
  expect(await screen.findByText('Nema članova za odabrane filtre.')).toBeInTheDocument();
  await userEvent.setup().click(screen.getByRole('button',{name:'Preuzmi CSV (svi rezultati)'}));
  expect(await screen.findByText(/Izvoz nije uspio/)).toBeInTheDocument();
});
