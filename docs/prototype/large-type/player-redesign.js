(() => {
 'use strict';
 const $ = s => document.querySelector(s), $$ = s => [...document.querySelectorAll(s)];
 const player=$('.player'), timeline=$('#timeline'), menu=$('#track-menu');
 const query=new URLSearchParams(location.search);
 if(query.get('focus')==='1')document.body.classList.add('focus');
 let type='episode', state='playing', index=1, position=1961, duration=2780;
 let dragging=false, touching=false, activeMenu=null, selectedSubtitle=0, selectedAudio=0;
 let hideTimer, feedbackTimer, clock, backTo;
 const episodeNames=['雨夜归来','月下疑踪','暗巷来客'];
 const movieNames=['山野回声','潮汐尽头','穿过赤色山谷'];
 const subtitles=[['简体中文','SRT'],['繁体中文','PGS'],['关闭字幕','']];
 const audio=[['国语','Dolby Digital · 5.1'],['粤语','Dolby Digital · 5.1'],['英语','DTS-HD · 5.1']];
 const icon = id => `<svg aria-hidden="true"><use href="#${id}"/></svg>`;
 const time = n => {n=Math.floor(n);return n>=3600?`${Math.floor(n/3600)}:${String(Math.floor(n/60)%60).padStart(2,'0')}:${String(n%60).padStart(2,'0')}`:`${Math.floor(n/60)}:${String(n%60).padStart(2,'0')}`;};
 const mustStay=()=>state!=='playing'||dragging||touching||activeMenu!==null;
 function show(){player.classList.remove('controls-hidden');scheduleHide();}
 function scheduleHide(){clearTimeout(hideTimer);if(!mustStay())hideTimer=setTimeout(()=>{if(!mustStay())player.classList.add('controls-hidden');},5000);}
 function notify(text){$('.feedback').textContent=text;$('.feedback').hidden=false;clearTimeout(feedbackTimer);feedbackTimer=setTimeout(()=>$('.feedback').hidden=true,1300);}
 function updateTime(){timeline.max=duration;timeline.value=position;timeline.style.setProperty('--progress',`${position/duration*100}%`);$('#elapsed').textContent=time(position);$('#duration').textContent=time(duration);timeline.setAttribute('aria-valuetext',`${time(position)}，共 ${time(duration)}`);}
 function setState(next){state=next;player.dataset.state=state;$('.buffer').hidden=state!=='buffering';const active=state!=='paused';$('#toggle use').setAttribute('href',active?'#pause':'#play');$('#toggle').setAttribute('aria-label',active?'暂停':'播放');$('#toggle .sr').textContent=active?'暂停':'播放';$$('[data-state]').filter(b=>b.tagName==='BUTTON').forEach(b=>b.setAttribute('aria-pressed',String(b.dataset.state===state)));show();}
 function content(next){closeMenu(false);type=next;index=next==='episode'?1:0;position=next==='episode'?1961:1098;renderContent();setState('playing');}
 function renderContent(){const isEpisode=type==='episode', isCollection=type==='collection';duration=isEpisode?2780:6070;$('#title').textContent=isEpisode?'魅影神捕':movieNames[index];$('#episode').textContent=isEpisode?`第${index+1}集 · ${episodeNames[index]}`:isCollection?`山海之间（系列） · 第${index+1}部`:'2025 · 电影';$('.art').classList.toggle('forest',!isEpisode);$('.central').classList.toggle('single',type==='movie');$('#previous-track span').textContent=isEpisode?'上一集':'上一部';$('#next-track span').textContent=isEpisode?'下一集':'下一部';$('#previous-track').disabled=index===0;$('#next-track').disabled=index===2;$('#sequence').textContent=isEpisode?`第 ${index+1} 集 / 共 3 集`:`第 ${index+1} 部 / 共 3 部`;$$('[data-content]').forEach(b=>b.setAttribute('aria-pressed',String(b.dataset.content===type)));updateTime();}
 function closeMenu(focus=true){if(!activeMenu)return;const old=activeMenu;activeMenu=null;menu.hidden=true;$('#subtitle').setAttribute('aria-expanded','false');$('#audio-track').setAttribute('aria-expanded','false');if(focus)old.focus({preventScroll:true});scheduleHide();}
 function placeMenu(){if(!activeMenu)return;let x=0,node=activeMenu;while(node&&node!==player){x+=node.offsetLeft;node=node.offsetParent;}const tip=menu.offsetLeft+menu.offsetWidth-x-activeMenu.offsetWidth/2;menu.style.setProperty('--tip',`${Math.max(18,Math.min(menu.offsetWidth-18,tip))-6}px`);}
 function openMenu(button,kind){if(activeMenu===button){closeMenu();return;}closeMenu(false);activeMenu=button;show();button.setAttribute('aria-expanded','true');menu.hidden=false;menu.setAttribute('aria-label',kind==='subtitle'?'字幕选项':'音轨选项');const entries=kind==='subtitle'?subtitles:audio, chosen=kind==='subtitle'?selectedSubtitle:selectedAudio;$('.track-options').replaceChildren();entries.forEach(([title,detail],i)=>{const b=document.createElement('button');b.className='track-option';b.setAttribute('role','menuitemradio');b.setAttribute('aria-checked',String(chosen===i));b.innerHTML=`<span class="track-copy">${title}${detail?`<small>${detail}</small>`:''}</span>${icon('check')}`;b.addEventListener('click',()=>{if(kind==='subtitle'){selectedSubtitle=i;$('.caption').hidden=i===2;}else selectedAudio=i;closeMenu();notify(kind==='subtitle'&&i===2?'已关闭字幕':`已切换：${title}`);});$('.track-options').append(b);});$('.track-options').setAttribute('role','menu');placeMenu();menu.querySelector('[aria-checked=true]').focus({preventScroll:true});}
 window.addEventListener('resize',placeMenu);
 $('#toggle').addEventListener('click',()=>setState(state==='paused'?'playing':'paused'));
 $$('.skip').forEach(b=>b.addEventListener('click',()=>{position=Math.max(0,Math.min(duration,position+Number(b.dataset.seek)));if(position===duration)setState('paused');updateTime();show();notify(Number(b.dataset.seek)<0?'后退 5 秒':'前进 15 秒');}));
 $('#subtitle').addEventListener('click',()=>openMenu($('#subtitle'),'subtitle'));
 $('#audio-track').addEventListener('click',()=>openMenu($('#audio-track'),'audio'));
 $('.surface').addEventListener('click',()=>{if(activeMenu){closeMenu();return;}if(player.classList.contains('controls-hidden'))show();else if(!mustStay()){clearTimeout(hideTimer);player.classList.add('controls-hidden');}});
 $('#previous-track').addEventListener('click',()=>change(-1));$('#next-track').addEventListener('click',()=>change(1));
 function change(delta){index=Math.max(0,Math.min(2,index+delta));position=0;renderContent();show();notify(type==='episode'?`第 ${index+1} 集`:`第 ${index+1} 部`);}
 timeline.addEventListener('pointerdown',()=>{dragging=true;show();});timeline.addEventListener('input',()=>{position=Number(timeline.value);updateTime();show();});timeline.addEventListener('change',()=>{dragging=false;if(position===duration)setState('paused');show();});
 document.addEventListener('pointerup',()=>{touching=false;dragging=false;scheduleHide();});document.addEventListener('pointercancel',()=>{touching=false;dragging=false;scheduleHide();});
 player.addEventListener('pointerdown',()=>{touching=true;clearTimeout(hideTimer);});
 player.addEventListener('pointerdown',e=>{if(activeMenu&&!e.target.closest('.track-menu,.tracks,.surface'))closeMenu(false);},true);
 document.addEventListener('keydown',e=>{if(e.key==='Escape'){if(activeMenu){e.preventDefault();closeMenu();}else show();}if(e.code==='Space'&&!e.target.closest('button,a,input')){e.preventDefault();$('#toggle').click();}});
 document.addEventListener('focusin',e=>{if(player.contains(e.target))show();});
 $$('[data-content]').forEach(b=>b.addEventListener('click',()=>content(b.dataset.content)));$$('button[data-state]').forEach(b=>b.addEventListener('click',()=>{closeMenu(false);setState(b.dataset.state);}));$('#show-controls').addEventListener('click',show);
 const focus=$('.focus-link');focus.href=`?focus=1&content=${query.get('content')||'episode'}`;
 clock=setInterval(()=>{if(state!=='playing'||dragging)return;position=Math.min(duration,position+1);updateTime();if(position===duration){if(type!=='movie'&&index<2)change(1);else{setState('paused');notify('播放结束 · 返回原页面（设计演示）');}}},1000);
 document.addEventListener('visibilitychange',()=>{if(document.hidden){backTo=state;clearTimeout(hideTimer);if(state==='playing')state='paused';}else{setState(backTo||state);backTo=null;}});
 window.addEventListener('pagehide',()=>{clearTimeout(hideTimer);clearTimeout(feedbackTimer);clearInterval(clock);});
 content(['episode','collection','movie'].includes(query.get('content'))?query.get('content'):'episode');
 if(query.get('state')==='paused'||query.get('state')==='buffering')setState(query.get('state'));
})();
