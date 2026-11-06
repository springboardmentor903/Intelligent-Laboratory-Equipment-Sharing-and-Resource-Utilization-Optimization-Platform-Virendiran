const monitoring = { page:0,size:12,status:'',admin:false,socket:null,retry:null,retryCount:0,closed:false };
const monitor$ = selector => document.querySelector(selector);
function statusMessage(text,error=false){const node=monitor$('#monitor-message');node.textContent=text;node.classList.toggle('error',error);}
function elapsedFromServerStart(start){if(!start)return '—';const seconds=Math.max(0,Math.floor((Date.now()-new Date(start).getTime())/1000));const h=Math.floor(seconds/3600),m=Math.floor(seconds%3600/60),s=seconds%60;return `${h}h ${String(m).padStart(2,'0')}m ${String(s).padStart(2,'0')}s`;}
async function fetchStatus(){
  const query=new URLSearchParams({page:monitoring.page,size:monitoring.size});if(monitoring.status)query.set('status',monitoring.status);
  const endpoint=monitoring.admin?'/api/admin/equipment/status':'/api/equipment/status';
  const response=await fetch(`${endpoint}?${query}`,{credentials:'same-origin'});
  if(response.status===401){location.replace('/');throw new Error('Please sign in again.');}
  const data=await response.json().catch(()=>({}));if(!response.ok)throw new Error(data.error||'Could not load equipment status.');return data;
}
function renderStatusPage(result){
  const host=monitor$('#equipment-status-list');host.replaceChildren();
  monitor$('#monitor-page').textContent=`Page ${result.page+1} of ${Math.max(1,result.totalPages)} · ${result.totalItems} equipment items`;
  monitor$('#monitor-previous').disabled=result.page===0;monitor$('#monitor-next').disabled=result.page+1>=result.totalPages;
  if(!result.items.length){statusMessage('No equipment matches this status filter.');return;}
  statusMessage(`${result.totalItems} equipment item${result.totalItems===1?'':'s'} loaded. Updates arrive automatically when equipment status changes.`);
  for(const item of result.items){
    const card=document.createElement('article');card.className='equipment-status-card';
    const head=document.createElement('div');head.className='equipment-status-header';const title=document.createElement('h2');title.textContent=item.equipmentName;const tag=document.createElement('span');tag.className='asset-tag';tag.textContent=item.assetTag||'No asset tag';head.append(title,tag,window.makeStatusBadge(item.status));card.append(head);
    const info=document.createElement('div');info.className='equipment-status-info';
    const current=document.createElement('div');
    if(item.inUse){current.replaceChildren();const duration=document.createElement('strong');duration.dataset.usageStart=item.usageStartTime;duration.textContent=`In use · ${elapsedFromServerStart(item.usageStartTime)}`;const start=document.createElement('span');start.textContent=`Started ${window.formatLabTime(item.usageStartTime)}`;const end=document.createElement('span');end.textContent=`Scheduled end ${window.formatLabTime(item.scheduledBookingEndTime)}`;current.append(duration,start,end);if(monitoring.admin&&item.currentUserName){const person=document.createElement('span');person.textContent=`Using: ${item.currentUserName}${item.currentUserEmail?` · ${item.currentUserEmail}`:''}`;current.append(person);}}
    else {current.textContent='No active usage session.';}
    info.append(current);
    const next=document.createElement('div');next.innerHTML='';if(item.nextBookingStartTime){const label=document.createElement('strong');label.textContent='Next confirmed booking';const when=document.createElement('span');when.textContent=`${window.formatLabTime(item.nextBookingStartTime)} – ${window.formatLabTime(item.nextBookingEndTime)}`;next.append(label,when);}else next.textContent='No upcoming confirmed booking.';info.append(next);card.append(info);host.append(card);
  }
}
setInterval(()=>{for(const node of document.querySelectorAll('[data-usage-start]'))node.textContent=`In use · ${elapsedFromServerStart(node.dataset.usageStart)}`;},1000);
async function loadStatus(){
  statusMessage('Loading equipment status…');monitor$('#equipment-status-list').replaceChildren();
  try{const result=await fetchStatus();renderStatusPage(result);}catch(error){statusMessage(`${error.message} Use Retry or Refresh status to try again.`,true);const retry=document.createElement('button');retry.className='calendar-button';retry.textContent='Retry';retry.addEventListener('click',loadStatus);monitor$('#equipment-status-list').replaceChildren(retry);}
}
function socketState(text,connected){const el=monitor$('#socket-state');el.textContent=text;el.classList.toggle('connected',connected);el.classList.toggle('disconnected',!connected);}
function connectSocket(){
  if(monitoring.closed||monitoring.socket&&[WebSocket.OPEN,WebSocket.CONNECTING].includes(monitoring.socket.readyState))return;
  const scheme=location.protocol==='https:'?'wss':'ws';const socket=new WebSocket(`${scheme}://${location.host}/ws/equipment`);monitoring.socket=socket;
  socket.addEventListener('open',()=>{monitoring.retryCount=0;socketState('Live updates connected.',true);loadStatus();});
  socket.addEventListener('message',event=>{try{const message=JSON.parse(event.data);if(message.type==='equipment-status-changed')loadStatus();}catch{}});
  socket.addEventListener('error',()=>socket.close());
  socket.addEventListener('close',()=>{
    if(monitoring.socket===socket)monitoring.socket=null;
    if(monitoring.closed)return;
    socketState('Live updates disconnected. Reconnecting…',false);
    const delay=Math.min(30_000,1000*(2**monitoring.retryCount++));
    monitoring.retry=setTimeout(connectSocket,delay);
  });
}
async function initializeMonitoring(){
  try{const me=await fetch('/api/auth/me');if(me.ok){const data=await me.json();monitor$('#monitor-user').textContent=data.user.name;}
    const admin=await fetch('/api/admin/session');monitoring.admin=admin.ok;
    monitor$('#monitor-logout').addEventListener('click',async()=>{await fetch('/api/auth/logout',{method:'POST'});if(monitoring.admin)await fetch('/api/admin/logout',{method:'POST'});location.replace('/');});
    await loadStatus();connectSocket();
  }catch(error){statusMessage(error.message,true);}
}
monitor$('#monitor-status').addEventListener('change',event=>{monitoring.status=event.target.value;monitoring.page=0;loadStatus();});
monitor$('#monitor-refresh').addEventListener('click',loadStatus);
monitor$('#monitor-previous').addEventListener('click',()=>{monitoring.page=Math.max(0,monitoring.page-1);loadStatus();});
monitor$('#monitor-next').addEventListener('click',()=>{monitoring.page++;loadStatus();});
window.addEventListener('pagehide',()=>{monitoring.closed=true;if(monitoring.retry)clearTimeout(monitoring.retry);if(monitoring.socket){const socket=monitoring.socket;monitoring.socket=null;socket.close(1000,'Page closed.');}});
initializeMonitoring();
