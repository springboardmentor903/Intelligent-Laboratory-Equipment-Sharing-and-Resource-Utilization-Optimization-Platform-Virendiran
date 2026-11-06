const state = { equipment: [], equipmentId: null, agreement: null, anchor: startOfWeek(new Date()), view: 'week', events: [], page: 0, pageSize: 10 };
const $ = selector => document.querySelector(selector);

function startOfWeek(date) { const d = new Date(date); d.setHours(0,0,0,0); d.setDate(d.getDate() - ((d.getDay()+6)%7)); return d; }
function addDays(date, amount) { const d = new Date(date); d.setDate(d.getDate()+amount); return d; }
function localDateTime(date) { const pad=n=>String(n).padStart(2,'0'); return `${date.getFullYear()}-${pad(date.getMonth()+1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`; }
function displayTime(value, options={dateStyle:'medium',timeStyle:'short'}) { return window.formatLabTime(value, options); }
function rangeEnd() { return addDays(state.anchor, state.view==='week'?7:1); }
function dateKey(date) { return `${date.getFullYear()}-${String(date.getMonth()+1).padStart(2,'0')}-${String(date.getDate()).padStart(2,'0')}`; }

async function api(url, options={}) {
  const response = await fetch(url,{credentials:'same-origin',headers:{'Content-Type':'application/json',...(options.headers||{})},...options});
  if(response.status===401){location.replace('/');throw new Error('Please sign in again.');}
  const data = response.status===204?null:await response.json().catch(()=>null);
  if(!response.ok){const error=new Error(data?.error||'The request could not be completed.');error.status=response.status;throw error;}
  return data;
}
function showMessage(node,message,error=false){node.textContent=message;node.classList.toggle('error',error);}
function agreementAllows(start,end){if(!state.agreement)return true;const a=state.agreement,startDate=dateKey(start),endDate=dateKey(new Date(end.getTime()-1));if(startDate<a.startDate||endDate>a.endDate)return false;if(a.allowedStart&&a.allowedEnd){if(startDate!==endDate)return false;const [sh,sm]=a.allowedStart.split(':').map(Number),[eh,em]=a.allowedEnd.split(':').map(Number);const startMinute=start.getHours()*60+start.getMinutes(),endMinute=end.getHours()*60+end.getMinutes();if(startMinute<sh*60+sm||endMinute>eh*60+em)return false;}return true;}
function renderAgreementBanner(){const host=$('#agreement-banner');host.replaceChildren();if(!state.agreement){host.hidden=true;return;}host.hidden=false;const title=document.createElement('strong');title.textContent=`Shared agreement for ${state.agreement.equipmentName}`;const text=document.createElement('p');text.textContent=`Agreement ${state.agreement.startDate} – ${state.agreement.endDate} · Allowed hours: ${state.agreement.allowedStart&&state.agreement.allowedEnd?`${state.agreement.allowedStart}–${state.agreement.allowedEnd}`:'Any operating hours'} · Minimum notice: ${state.agreement.minimumNoticeHours} hours · Maximum booking: ${state.agreement.maximumBookingHours} hours · Fee: ${new Intl.NumberFormat(undefined,{style:'currency',currency:'INR'}).format(state.agreement.feePerHour)} per hour`;host.append(title,text);}

async function loadEquipment(){
  state.equipment=await api('/api/instruments');
  const select=$('#equipment-select'); select.replaceChildren();
  for(const item of state.equipment){const option=document.createElement('option');option.value=item.id;option.textContent=item.name;option.disabled=item.status.toLowerCase()!=='available';select.append(option);}
  const requested=new URLSearchParams(location.search).get('equipmentId');
  const agreementId=new URLSearchParams(location.search).get('agreementId');
  state.agreement=null;
  if(agreementId){const agreementPage=await api('/api/sharing/agreements?activeOnly=true');state.agreement=agreementPage.items.find(a=>String(a.id)===agreementId&&a.hasAccessGrant)||null;if(!state.agreement)throw new Error('The shared agreement is expired, revoked, or not assigned to your account.');}
  renderAgreementBanner();
  const requestedEquipment=state.equipment.find(x=>String(x.id)===requested&&x.status.toLowerCase()==='available');
  const firstBookable=state.equipment.find(x=>x.status.toLowerCase()==='available');
  state.equipmentId=requestedEquipment?String(requestedEquipment.id):(firstBookable?String(firstBookable.id):null);
  if(state.equipmentId)select.value=state.equipmentId;
  $('#selected-equipment-label').textContent=state.equipment.find(x=>String(x.id)===state.equipmentId)?.name||'';
  await loadCalendar();
}
async function loadCalendar(){
  const calendar=$('#calendar');calendar.replaceChildren();
  if(!state.equipmentId){showMessage($('#calendar-message'),'No equipment is available.');return;}
  const days=state.view==='week'?7:1,end=rangeEnd();
  $('#range-label').textContent=state.view==='day'?displayTime(state.anchor,{dateStyle:'full'}):`${displayTime(state.anchor,{dateStyle:'medium'})} – ${displayTime(addDays(end,-1),{dateStyle:'medium'})}`;
  calendar.classList.toggle('day-view',state.view==='day');
  const startLocal=new Date(state.anchor);startLocal.setHours(0,0,0,0);
  const endLocal=new Date(end);endLocal.setHours(0,0,0,0);
  try{state.events=await api(`/api/equipment/${state.equipmentId}/availability?from=${encodeURIComponent(startLocal.toISOString())}&to=${encodeURIComponent(endLocal.toISOString())}`);}
  catch(error){showMessage($('#calendar-message'),error.message,true);return;}
  showMessage($('#calendar-message'),'Select a free half-hour start time. Bookings are limited to 8 hours.');
  const corner=document.createElement('div');corner.className='calendar-head';corner.textContent='TIME';calendar.append(corner);
  const visibleDates=Array.from({length:days},(_,i)=>addDays(state.anchor,i));
  for(const day of visibleDates){const h=document.createElement('div');h.className='calendar-head';h.textContent=displayTime(day,{weekday:'short',month:'numeric',day:'numeric'});calendar.append(h);}
  const now=new Date();
  for(let mins=8*60;mins<20*60;mins+=30){
    const label=document.createElement('div');label.className='time-label';label.textContent=mins%60===0?`${String(Math.floor(mins/60)).padStart(2,'0')}:00`:'';calendar.append(label);
    for(const day of visibleDates){
      const cell=document.createElement('button');cell.type='button';cell.className='calendar-cell';cell.setAttribute('aria-label',`${displayTime(day,{dateStyle:'medium'})} ${String(Math.floor(mins/60)).padStart(2,'0')}:${String(mins%60).padStart(2,'0')}`);
      const start=new Date(day);start.setHours(Math.floor(mins/60),mins%60,0,0);const finish=new Date(start.getTime()+30*60000);
      if(start<now){cell.classList.add('past');cell.disabled=true;}
      if(!agreementAllows(start,finish)){cell.classList.add('agreement-blocked');cell.disabled=true;}
      const event=state.events.find(e=>new Date(e.startTime)<finish&&new Date(e.endTime)>start);
      if(event){cell.classList.add('occupied');cell.disabled=true;const badge=document.createElement('span');const statusClass=event.status==='PENDING_APPROVAL'?'pending':event.status==='IN_USE'?'inuse':'confirmed';badge.className=`slot-event ${statusClass}`;badge.textContent=event.status.replaceAll('_',' ');cell.append(badge);}
      else if(!cell.disabled){cell.addEventListener('click',()=>selectSlot(start));}
      calendar.append(cell);
    }
  }
}
function selectSlot(start){const end=new Date(start.getTime()+60*60000);if(!agreementAllows(start,end)){showMessage($('#form-message'),'Choose a time fully inside the agreement dates and allowed hours.',true);return;}if(state.events.some(event=>new Date(event.startTime)<end&&new Date(event.endTime)>start)){showMessage($('#form-message'),'That one-hour period overlaps an occupied slot. Choose another start time.',true);return;}$('#booking-start').value=localDateTime(start);$('#booking-end').value=localDateTime(end);$('#booking-purpose').focus();showMessage($('#form-message'),'Selected time loaded into the booking form.');}

async function loadMyBookings(){
  const status=$('#status-select').value;const query=new URLSearchParams({page:state.page,size:state.pageSize});if(status)query.set('status',status);
  const host=$('#my-bookings');host.replaceChildren();
  try{
    const page=await api(`/api/bookings/my?${query}`);$('#page-label').textContent=`Page ${page.page+1} of ${Math.max(1,page.totalPages)} · ${page.totalItems} bookings`;
    $('#page-back').disabled=page.page===0;$('#page-forward').disabled=page.page+1>=page.totalPages;
    if(!page.items.length){host.innerHTML='<p class="empty-message">No bookings found for this filter.</p>';return;}
    for(const booking of page.items){
      const card=document.createElement('article');card.className='my-booking';
      const title=document.createElement('h3');title.textContent=booking.equipmentName;
      const badge=window.makeStatusBadge(booking.status,'booking-badge');
      const when=document.createElement('p');when.textContent=`${displayTime(booking.startTime)} – ${displayTime(booking.endTime)}`;
      const purpose=document.createElement('p');purpose.textContent=booking.purpose;
      card.append(title,badge,when,purpose);
      if(booking.agreementId){const fee=document.createElement('p');fee.textContent=`Shared agreement fee: ${new Intl.NumberFormat(undefined,{style:'currency',currency:'INR'}).format(booking.feePerHour)} per hour · Estimated ${new Intl.NumberFormat(undefined,{style:'currency',currency:'INR'}).format(booking.estimatedFee)}`;card.append(fee);}
      if(booking.rejectionReason){const rejected=document.createElement('p');rejected.textContent=`Rejection reason: ${booking.rejectionReason}`;card.append(rejected);}
      const actions=document.createElement('div');actions.className='booking-actions';
      addUsageAction(actions,booking);
      const details=document.createElement('button');details.textContent='Details and history';details.addEventListener('click',()=>showDetails(booking.id));actions.append(details);
      if(['PENDING_APPROVAL','CONFIRMED'].includes(booking.status)&&new Date(booking.startTime)>new Date()) {const cancel=document.createElement('button');cancel.className='cancel-action';cancel.textContent='Cancel';cancel.addEventListener('click',()=>cancelBooking(booking.id));actions.append(cancel);}
      card.append(actions);host.append(card);
    }
  }catch(error){host.innerHTML=`<p class="empty-message">${error.message}</p>`;}
}
function addUsageAction(host,booking){
  const now=Date.now(),start=new Date(booking.startTime).getTime(),end=new Date(booking.endTime).getTime();
  const action=booking.status==='CONFIRMED'&&now>=start-15*60_000&&now<=end?'check-in':booking.status==='IN_USE'?'check-out':null;
  if(!action)return;
  const button=document.createElement('button');button.textContent=action==='check-in'?'Check in':'Check out';button.className='usage-action';
  button.addEventListener('click',async()=>{
    button.disabled=true;const message=document.createElement('span');message.className='usage-result';host.append(message);message.textContent=action==='check-in'?'Checking in…':'Checking out…';
    try{
      await api(`/api/bookings/${booking.id}/${action}`,{method:'POST',...(action==='check-out'?{body:JSON.stringify({})}:{})});
      message.textContent=action==='check-in'?'Checked in.':'Checked out.';
      await Promise.all([loadMyBookings(),loadCalendar()]);
      if($('#booking-details').open)await showDetails(booking.id);
    }catch(error){message.textContent=error.message;button.disabled=false;}
  });
  host.append(button);
}
async function cancelBooking(id){if(!confirm('Cancel this booking?'))return;try{await api(`/api/bookings/${id}/cancel`,{method:'POST',body:JSON.stringify({reason:'Cancelled by the user.'})});await Promise.all([loadMyBookings(),loadCalendar()]);}catch(error){alert(error.message);}}
async function showDetails(id){
  const [booking,history]=await Promise.all([api(`/api/bookings/${id}`),api(`/api/bookings/${id}/history`)]);
  const host=$('#details-content');host.replaceChildren();const h=document.createElement('h2');h.textContent=booking.equipmentName;const p=document.createElement('p');p.textContent=`${displayTime(booking.startTime)} – ${displayTime(booking.endTime)} · ${booking.status.replaceAll('_',' ')}`;const purpose=document.createElement('p');purpose.textContent=booking.purpose;host.append(h,p,purpose);if(booking.agreementId){const fee=document.createElement('p');fee.textContent=`Fee per hour: ${new Intl.NumberFormat(undefined,{style:'currency',currency:'INR'}).format(booking.feePerHour)} · Estimated fee: ${new Intl.NumberFormat(undefined,{style:'currency',currency:'INR'}).format(booking.estimatedFee)}`;host.append(fee);}
  const usageActions=document.createElement('div');usageActions.className='booking-actions';addUsageAction(usageActions,booking);if(usageActions.childElementCount)host.append(usageActions);
  if(booking.rejectionReason){const r=document.createElement('p');r.textContent=`Rejection reason: ${booking.rejectionReason}`;host.append(r);}
  const heading=document.createElement('h3');heading.textContent='History';host.append(heading);const list=document.createElement('div');list.className='history-list';
  for(const item of history){const row=document.createElement('div');row.className='history-item';const title=document.createElement('strong');title.textContent=`${item.previousStatus||'New'} → ${item.newStatus}`;const meta=document.createElement('p');meta.textContent=`${item.changedBy} · ${displayTime(item.changedAt)}`;row.append(title,meta);if(item.remarks){const remark=document.createElement('p');remark.textContent=item.remarks;row.append(remark);}list.append(row);}host.append(list);$('#booking-details').showModal();
}

$('#booking-form').addEventListener('submit',async event=>{
  event.preventDefault();const startValue=$('#booking-start').value,endValue=$('#booking-end').value,purpose=$('#booking-purpose').value.trim();
  const start=new Date(startValue),end=new Date(endValue);if(!purpose||!startValue||!endValue||end<=start||start<=new Date()){showMessage($('#form-message'),'Enter a purpose, a future start time, and an end time after the start.',true);return;}if(state.agreement){const duration=(end-start)/3600000;if(!agreementAllows(start,end)){showMessage($('#form-message'),'The booking must fit inside the agreement dates and allowed hours.',true);return;}if(start.getTime()-Date.now()<state.agreement.minimumNoticeHours*3600000){showMessage($('#form-message'),`This agreement requires ${state.agreement.minimumNoticeHours} hours of notice.`,true);return;}if(duration>Math.min(8,state.agreement.maximumBookingHours)){showMessage($('#form-message'),`Bookings under this agreement cannot exceed ${Math.min(8,state.agreement.maximumBookingHours)} hours.`,true);return;}}
  const submit=$('#booking-submit');submit.disabled=true;showMessage($('#form-message'),'Submitting booking…');
  try{const booking=await api('/api/bookings',{method:'POST',body:JSON.stringify({equipmentId:Number(state.equipmentId),startTime:start.toISOString(),endTime:end.toISOString(),purpose})});showMessage($('#form-message'),booking.estimatedFee==null?'Booking submitted.':`Booking ${booking.status.replaceAll('_',' ').toLowerCase()}. Estimated fee: ${new Intl.NumberFormat(undefined,{style:'currency',currency:'INR'}).format(booking.estimatedFee)} (${new Intl.NumberFormat(undefined,{style:'currency',currency:'INR'}).format(booking.feePerHour)} per hour).`);$('#booking-purpose').value='';await Promise.all([loadMyBookings(),loadCalendar()]);}
  catch(error){showMessage($('#form-message'),error.status===409?'This slot was just taken. Please choose another time.':error.message,true);if(error.status===409)await loadCalendar();}
  finally{submit.disabled=false;}
});
$('#equipment-select').addEventListener('change',event=>{state.equipmentId=event.target.value;$('#selected-equipment-label').textContent=state.equipment.find(x=>String(x.id)===state.equipmentId)?.name||'';loadCalendar();});
$('#view-select').addEventListener('change',event=>{state.view=event.target.value;if(state.view==='week')state.anchor=startOfWeek(state.anchor);loadCalendar();});
$('#previous-range').addEventListener('click',()=>{state.anchor=addDays(state.anchor,state.view==='week'?-7:-1);loadCalendar();});$('#next-range').addEventListener('click',()=>{state.anchor=addDays(state.anchor,state.view==='week'?7:1);loadCalendar();});$('#today-range').addEventListener('click',()=>{state.anchor=state.view==='week'?startOfWeek(new Date()):new Date();state.anchor.setHours(0,0,0,0);loadCalendar();});
$('#status-select').addEventListener('change',()=>{state.page=0;loadMyBookings();});$('#page-back').addEventListener('click',()=>{state.page=Math.max(0,state.page-1);loadMyBookings();});$('#page-forward').addEventListener('click',()=>{state.page++;loadMyBookings();});$('#close-details').addEventListener('click',()=>$('#booking-details').close());$('#logout').addEventListener('click',async()=>{await fetch('/api/auth/logout',{method:'POST'});location.replace('/');});
api('/api/auth/me').then(data=>{$('#nav-user').textContent=data.user.name;return Promise.all([loadEquipment(),loadMyBookings()]);}).catch(error=>showMessage($('#calendar-message'),error.message,true));
