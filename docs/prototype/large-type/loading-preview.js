(() => {
'use strict';
const $=s=>document.querySelector(s), esc=v=>String(v).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const params=new URLSearchParams(location.search);
const pageNames={home:'首页',library:'媒体库',detail:'媒体详情',search:'搜索'};
let page=pageNames[params.get('page')]?params.get('page'):'home';
let phase=params.get('phase')||'first', state=params.get('state')||'loading', latest=params.get('home')==='latest', timer, searchTimer, query='山';
const phaseNames={first:'首次加载',refresh:'下拉刷新',more:'分页加载',season:'切换季'};
const stateNames={loading:'加载中',success:'已完成',slow:'加载较慢',error:'加载失败',empty:'空数据',end:'全部加载完'};
const supported={home:['first','refresh'],library:['first','refresh','more'],detail:['first','season'],search:['first']};
const media=[['detective','魅影神捕','剧集','2026',0],['forest','山野回声','电影','2025',1],['neon','夜港来信','剧集','2026',2],['lighthouse','潮汐尽头','电影','2025',3],['desert','穿过赤色山谷','电影','2024',4],['palace','长夜未央','剧集','2025',5]];
const icons={home:'<path d="m3 10 9-7 9 7v11h-6v-7H9v7H3Z"/>',library:'<rect x="4" y="3" width="16" height="18" rx="1"/><path d="M7 8h10M7 12h10M7 16h10"/>',settings:'<path d="m9 3-.6 2.2-2 .9-2-.6-2 3.5 1.6 1.6v2.5L2.4 15l2 3.5 2.2-.5 1.8 1L9 21h4l.6-2.1 1.9-1 2.1.6 2-3.5-1.6-1.6v-2.5l1.6-1.6-2-3.5-2.1.6-1.9-1L13 3Z"/><circle cx="11" cy="12" r="3"/>'};
const svg=paths=>'<svg viewBox="0 0 24 24" aria-hidden="true">'+paths+'</svg>';
const art=i=>'<span class="art" style="--x:'+((i%3)*50)+'%;--y:'+(i<3?0:100)+'%" aria-hidden="true"></span>';
const block=(cls='')=>'<span class="skeleton '+cls+'" aria-hidden="true"></span>';
const spinner=()=>'<span class="spinner" aria-hidden="true"></span>';
const filters=()=>'<div class="demoFilters" aria-label="媒体库标签"><span>全部</span><span>电影</span><span>电视剧</span></div>';
const slowNote=()=>state==='slow'?'<div class="loadLine" role="status">加载较慢，请稍候…</div>':'';
const skGrid=count=>'<div class="skeletonGrid" aria-hidden="true">'+Array.from({length:count},()=>'<div>'+block('skeletonPoster')+block('skeletonText')+block('skeletonText short')+'</div>').join('')+'</div>';
const skEpisodes=()=>'<div aria-hidden="true">'+Array.from({length:3},()=>'<div class="skeletonEpisode">'+block('thumb')+'<div class="copy">'+block('skeletonText')+block('skeletonText short')+'</div></div>').join('')+'</div>';
function empty(title,text,button='重试') {return '<div class="emptyState"><h2>'+esc(title)+'</h2><p>'+esc(text)+'</p>'+(button?'<button data-retry>'+esc(button)+'</button>':'')+'</div>';}
function initialFailure(){return empty('加载失败','请检查网络后重试。');}
function poster(m){return '<a class="card demoPoster" href="home-resume.html?screen=detail&item='+m[0]+'"><span class="cover">'+art(m[4])+'</span><span class="name">'+m[1]+'</span><span class="sub">'+m[2]+' · '+m[3]+'</span></a>';}
function grid(items=media){return '<div class="grid '+(state==='success'?'contentReady':'')+'">'+items.map(poster).join('')+'</div>';}
function homeCards(){
 const items=latest?[media[3],media[2],media[5]]:[media[0],media[1],media[3]];
 return '<h2 class="demoHeader">'+(latest?'最近添加':'继续观看')+'</h2>'+items.map((m,i)=>{
 const episode=!latest&&i===0;
 return '<div class="demoHomeCard"><span class="cover landscape">'+art(m[4])+'<span class="resumeShade"></span><span class="resumeType">'+m[2]+'</span>'+(episode?'<span class="resumeEpisode">第1集</span>':'')+(!latest?'<span class="watchTime">'+['32:41 / 46:20','18:18 / 1:41:10','5:14 / 1:43:00'][i]+'</span><span class="progress"><i style="width:'+([70,18,5][i])+'%"></i></span>':'')+'<a class="quickPlayHit" aria-label="'+(latest?'播放':'继续观看')+' '+m[1]+'" href="home-resume.html?screen=player"><svg class="quickPlayIcon" viewBox="0 0 34 34" aria-hidden="true"><path d="M12.5,9.5 L26,17 L12.5,24.5 Z"/></svg></a></span><span class="cardCopy"><span class="name">'+m[1]+'<span class="cardYear">（'+m[3]+'）</span></span></span><button class="demoBodyAction" aria-label="查看 '+m[1]+' 详情" data-detail="'+m[0]+'"></button></div>';
 }).join('');
}
function home(){
 if(phase==='first'&&(state==='loading'||state==='slow'))return block('skeletonTitle')+Array.from({length:2},()=>'<div class="skeletonHome">'+block('skeletonCover')+block('skeletonText')+'</div>').join('')+slowNote();
 if(state==='error'&&phase==='first')return initialFailure();
 if(state==='empty')return empty('暂无可播放的影片','续播记录和最近添加均为空。','');
 return '<div class="'+(state==='success'?'contentReady':'')+'">'+homeCards()+'</div>';
}
function moreFooter(){
 if(state==='error')return '<div class="loadLine" role="status">加载失败 <button data-retry>重试</button></div>';
 if(state==='end')return '<div class="loadLine">已显示全部</div>';
 if(state==='success')return '<div class="loadLine">已加载新内容</div>';
 return '<div class="loadLine" role="status">'+spinner()+(state==='slow'?'加载较慢，请稍候…':'正在加载更多…')+'</div>';
}
function library(){
 let content='';
 if(phase==='first'&&(state==='loading'||state==='slow'))content=skGrid(4)+slowNote();
 else if(state==='error'&&phase==='first')content=initialFailure();
 else if(state==='empty')content=empty('这个媒体库暂无内容','可以切换其他媒体库查看。','');
 else content=grid(phase==='more'&&state!=='success'?media.slice(0,4):media);
 return content+(phase==='more'?'<div id="load-footer">'+moreFooter()+'</div>':'');
}
function episodes(){return [1,2,3].map((n)=>'<button class="episode" data-player><span class="cover landscape">'+art(0)+'</span><span class="episodeInfo"><span class="name">第'+n+'集 · '+['傀儡惊现','月下疑踪','灯下旧案'][n-1]+'</span><span class="sub">44 分钟</span></span></button>').join('');}
function detail(){
 if(phase==='first'&&(state==='loading'||state==='slow'))return block('skeletonHero')+'<div class="skeletonDetailRibbon"><div class="skeletonIdentity">'+block('poster')+'<div class="copy">'+block('skeletonText')+block('skeletonText short')+'</div></div>'+block('skeletonButton')+'</div><div class="sheet">'+block('skeletonText')+block('skeletonText')+block('skeletonText short')+skEpisodes()+slowNote()+'</div>';
 if(phase==='first'&&state==='error')return '<div class="sheet">'+initialFailure()+'</div>';
 if(phase==='first'&&state==='empty')return '<div class="sheet">'+empty('未找到这部影片','影片可能已移除。','返回媒体库')+'</div>';
 const pending=phase==='season'&&(state==='loading'||state==='slow');
 const list=pending?skEpisodes()+slowNote():phase==='season'&&state==='error'?empty('集列表加载失败','剧集资料保持不变。'):phase==='season'&&state==='empty'?empty('本季暂无可播放单集','可以切换其他季查看。',''):episodes();
 return '<div class="detail largeDetail"><div class="heroCover">'+art(0)+'<span class="shade"></span></div><div class="detailRibbon"><div class="detailIdentity"><div class="cover detailPoster">'+art(0)+'</div><div class="detailHeading"><h1>魅影神捕</h1><p class="meta">2026 · 剧集</p></div></div><div class="actions"><button class="detailPlay" data-player>▶ 继续观看</button></div></div><div class="sheet"><p class="summary">离奇旧案再现江城，神捕黎斯循着傀儡的来历追查，在迷雾里拼起多年未解的真相。</p><h2>选集</h2><div class="seasonTabs"><button class="chip" data-season="1" aria-pressed="'+(phase!=='season')+'">第1季</button><button class="chip" data-season="2" aria-pressed="'+(phase==='season')+'">第2季</button></div><div id="episode-list">'+list+'</div></div></div>';
}
function search(){
 let results='';
 if(!query)results=empty('搜索影片','请输入片名。','');
 else if(state==='loading'||state==='slow')results=skGrid(4)+slowNote();
 else if(state==='error')results=initialFailure();
 else if(state==='empty')results=empty('没有找到匹配的影片','试试其他片名或关键词。','');
 else { const matches=media.filter(m=>m[1].includes(query)); results=matches.length?grid(matches):empty('没有找到匹配的影片','试试其他片名或关键词。',''); }
 return '<div class="demoSearch"><input id="demo-query" type="search" aria-label="搜索片名" placeholder="搜索片名" value="'+esc(query)+'"></div><div id="search-results">'+results+'</div>';
}
function controls(){
 $('#page-options').innerHTML=Object.entries(pageNames).map(([id,title])=>'<button data-page="'+id+'" aria-pressed="'+(page===id)+'">'+title+'</button>').join('');
 $('#phase-options').innerHTML=Object.entries(phaseNames).map(([id,title])=>'<button data-phase="'+id+'" aria-pressed="'+(phase===id)+'" '+(!supported[page].includes(id)?'disabled':'')+'>'+(page==='search'&&id==='first'?'搜索结果':title)+'</button>').join('');
 $('#state-options').innerHTML=Object.entries(stateNames).map(([id,title])=>'<button data-state="'+id+'" aria-pressed="'+(state===id)+'" '+((id==='end'&&phase!=='more')||(id==='empty'&&phase==='more')?'disabled':'')+'>'+title+'</button>').join('');
 $('#home-example').disabled=page!=='home';
 $('#home-example').textContent=latest?'切换继续观看示例':'切换最近添加示例';
 const rules={home:'有续播仅显示继续观看；无续播才显示最近添加。首次空数据表示两者都为空。首页不新增分页。',library:'首次加载为两列海报骨架；刷新保留列表；分页只更新底部。切换分类按首次加载处理，已缓存分类沿用内容。',detail:'首屏保留顶部导航；切季只替换集列表，背景、封面和简介不闪动。不新增下拉刷新或分页。',search:'输入框和关键词始终保留，350ms 后检索；仅结果区显示骨架。新关键词不混入旧结果。不新增分页或下拉刷新。'};
 $('#review-note').textContent=rules[page]+' 当前示例状态固定展示；点「演示加载完成」可观看约2秒的模拟流程。';
}
function render(){
 if(!supported[page].includes(phase))phase='first';
 if(!(state in stateNames)||state==='end'&&phase!=='more'||state==='empty'&&phase==='more')state='loading';
 clearTimeout(timer);
 const focused=document.activeElement;
 const focusKey=focused?.closest(".notes")?["page","phase","state"].find(key=>focused.dataset[key]):null;
 const focusValue=focusKey?focused.dataset[focusKey]:null;
 controls();
 if(focusKey)document.querySelector(".notes [data-"+focusKey+"=\""+focusValue+"\"]")?.focus({preventScroll:true});
 $('.shell').dataset.screen=page==='detail'?'detail':page;
 const searchIcon=svg('<circle cx="10.5" cy="10.5" r="6.5"/><path d="m15.5 15.5 5 5"/>');
 $('.top').innerHTML='<div class="brand">'+(page==='home'?'<img class="mark" src="../logo.png" alt="">z-jellyfin':'<button class="largeTool" data-page="home" aria-label="返回">'+svg('<path d="m15 5-7 7 7 7"/>')+'<span>返回</span></button><span class="toplabel">'+pageNames[page]+'</span>')+'</div><div class="tools">'+(page!=='search'?'<button class="largeTool" data-page="search">'+searchIcon+'<span>搜索</span></button>':'')+'<a class="largeTool" href="home-resume.html?screen=settings" aria-label="设置">'+svg(icons.settings)+'<span>设置</span></a></div>';
 $('#library-filters').hidden=page!=='library';$('#library-filters').innerHTML=page==='library'?filters():'';
 const active=page==='home'?'home':'library';
 $('.bottom').innerHTML=[['home','首页'],['library','媒体库'],['settings','设置']].map(([id,name])=>'<button '+(id==='settings'?'data-settings':'data-page="'+id+'"')+' aria-current="'+(id===active?'page':'false')+'"><span>'+svg(icons[id])+'</span>'+name+'</button>').join('');
 const main=$('main');main.className=page==='detail'?'':'pad';main.setAttribute('aria-busy',String(state==='loading'||state==='slow'));main.innerHTML=({home,library,detail,search}[page])();if(phase==='first'&&(state==='loading'||state==='slow'))main.insertAdjacentHTML('afterbegin','<span class="srOnly" role="status">正在加载内容</span>');
 const isRefresh=phase==='refresh'&&(state==='loading'||state==='slow');
 $('#refresh-indicator').hidden=!isRefresh;$('#refresh-indicator').innerHTML=spinner();$('#refresh-indicator').setAttribute('role','status');$('#refresh-indicator').setAttribute('aria-label','正在刷新');
 const notice=$('#inline-notice');notice.hidden=!(phase==='refresh'&&(state==='error'||state==='slow'));notice.innerHTML=state==='error'?'刷新失败，保留原有内容。<button data-retry>重试</button>':'刷新较慢，原有内容仍可浏览。';notice.setAttribute('role','status');
 const args=new URLSearchParams({page,phase,state,home:latest?'latest':'resume'});history.replaceState(null,'','?'+args.toString());
 if(phase==='more')main.scrollTop=main.scrollHeight;
 if(phase==='season')$('#episode-list').scrollIntoView({block:'end'});
}
function completeDemo(){state='loading';render();timer=setTimeout(()=>{state='success';render();},2000);}
document.addEventListener('click',e=>{
 const b=e.target.closest('button');if(!b||b.disabled)return;const d=b.dataset;
 clearTimeout(timer);clearTimeout(searchTimer);
 if(d.page){page=d.page;phase='first';state='loading';render();}
 else if(d.phase){phase=d.phase;state='loading';render();}
 else if(d.state){state=d.state;render();}
 else if('retry' in d){if(page==='detail'&&phase==='first'&&state==='empty'){page='library';phase='first';}completeDemo();}
 else if(d.season){phase=d.season==='2'?'season':'first';completeDemo();}
 else if(b.id==='replay')completeDemo();
 else if(b.id==='home-example'){latest=!latest;state='success';render();}
 else if(d.detail)location.href='home-resume.html?screen=detail&item='+d.detail;
 else if('player' in d)location.href='home-resume.html?screen=player';
 else if('settings' in d)location.href='home-resume.html?screen=settings';
});
document.addEventListener('input',e=>{
 if(e.target.id!=='demo-query')return;query=e.target.value;clearTimeout(searchTimer);
 searchTimer=setTimeout(()=>{const focus=document.activeElement===e.target;state='loading';render();if(focus)$('#demo-query').focus({preventScroll:true});},350);
});
window.addEventListener('pagehide',()=>{clearTimeout(timer);clearTimeout(searchTimer);});
render();
})();
const mobileReview=document.createElement('button');mobileReview.className='reviewMobile';mobileReview.textContent='预览状态';mobileReview.setAttribute('aria-expanded','false');mobileReview.onclick=()=>{const opened=document.body.classList.toggle('reviewOpen');mobileReview.setAttribute('aria-expanded',String(opened));mobileReview.textContent=opened?'收起评审控件':'预览状态';};document.body.append(mobileReview);
/* Dragging a preview card must not follow the detail/play links at a scroll boundary. */
let previewDrag=null;
document.addEventListener('pointerdown',e=>{if(!e.target.closest('.demoHomeCard')){previewDrag=null;return;}previewDrag={id:e.pointerId,x:e.clientX,y:e.clientY,blocked:!e.isPrimary};},true);
document.addEventListener('pointermove',e=>{if(!previewDrag||e.pointerId!==previewDrag.id)return;const dx=e.clientX-previewDrag.x,dy=e.clientY-previewDrag.y;if(dx*dx+dy*dy>64)previewDrag.blocked=true;},true);
document.addEventListener('pointercancel',()=>{if(previewDrag)previewDrag.blocked=true;},true);
document.addEventListener('click',e=>{if(e.detail!==0&&previewDrag?.blocked&&e.target.closest('.demoHomeCard')){e.preventDefault();e.stopImmediatePropagation();}},true);
