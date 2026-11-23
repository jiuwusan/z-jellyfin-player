(() => {
'use strict';
const $=s=>document.querySelector(s), stage=$('.prepStage');
const allowed=['waiting','slow','error'];
const initial=new URLSearchParams(location.search).get('state');
let state=allowed.includes(initial)?initial:'waiting', outcome='success', timers=[], playing=true, position=1961, hideTimer;
const startPosition=1961, duration=2780;
const copy={waiting:'正常等待：固定展示超过150ms后的转圈状态，不显示加载文字。',slow:'加载较慢：超过6秒后显示提示，返回始终可用，不按等待时长自动判定失败。',error:'失败：停止转圈。点「重试播放」从32:41重新准备，沿用1080p／4 Mbps上限。',ready:'已模拟首帧：封面淡出，控件解除禁用。此处为静帧示意，完整播放器交互仍以原设计为准。'};
function clearTimers(){timers.forEach(clearTimeout);timers=[];clearTimeout(hideTimer);}
function clock(value){const sec=Math.floor(value);return Math.floor(sec/60)+':'+String(sec%60).padStart(2,'0');}
function updateTime(){ $('#prep-time').textContent=clock(position)+' / '+clock(duration);$('#prep-seek').value=position; }
function autoHide(){clearTimeout(hideTimer);stage.classList.remove('prepControlsHidden');if(state==='ready'&&playing)hideTimer=setTimeout(()=>stage.classList.add('prepControlsHidden'),5000);}
function render(){
 stage.dataset.state=state;
 $('.prepWaiting').hidden=state==='error'||state==='ready';$('.prepError').hidden=state!=='error';$('.prepSlow').hidden=state!=='slow';$('.prepCenter').hidden=state==='ready';
 $('.prepSpinner').hidden=false;
 document.querySelectorAll('[data-state]').forEach(b=>b.setAttribute('aria-pressed',String(b.dataset.state===state)));
 document.querySelectorAll('[data-outcome]').forEach(b=>b.setAttribute('aria-pressed',String(b.dataset.outcome===outcome)));
 document.querySelectorAll('[data-ready-action]').forEach(b=>{b.disabled=state!=='ready';});
 $('#prep-seek').disabled=state!=='ready';
 $('#prep-seek').setAttribute('aria-label',state==='ready'?'播放进度':'播放进度，准备完成后可调整');
 const tracks=document.querySelectorAll('.prepTools button');
 tracks.forEach((b,i)=>b.setAttribute('aria-label',(i===0?'字幕':'音轨')+(state==='ready'?'':'，视频准备完成后可用')));
 const toggle=document.querySelector('.prepToggle');
 toggle.setAttribute('aria-label',state==='ready'?(playing?'暂停':'播放'):'播放，视频准备完成后可用');
 toggle.querySelector('path').setAttribute('d',playing?'M8 5h3v14H8zM15 5h3v14h-3z':'M8 5L20 12L8 19Z');
 $('.prepBottom').setAttribute('aria-label',state==='ready'?'播放控制':'播放控制，准备完成前不可用');
 $('#review-status').textContent=copy[state];$('#first-frame').disabled=state==='error'||state==='ready';
 updateTime();
 history.replaceState(null,'','?state='+state);
 autoHide();
}
function setState(next){clearTimers();state=next;if(next!=='ready')position=startPosition;playing=true;render();}
function firstFrame(){if(state!=='waiting'&&state!=='slow')return;setState('ready');}
function begin(retry=false){
 setState('waiting');$('.prepSpinner').hidden=true;
 timers.push(setTimeout(()=>{if(state==='waiting')$('.prepSpinner').hidden=false;},150));
 timers.push(setTimeout(()=>{if(state==='waiting'){state='slow';render();}},6000));
 if(retry)timers.push(setTimeout(()=>{if(outcome==='error')setState('error');else firstFrame();},2000));
}
document.addEventListener('click',e=>{
 const b=e.target.closest('button');if(!b||b.disabled)return;
 if(b.dataset.state){setState(b.dataset.state);return;}
 if(b.dataset.outcome){outcome=b.dataset.outcome;document.querySelectorAll('[data-outcome]').forEach(x=>x.setAttribute('aria-pressed',String(x.dataset.outcome===outcome)));return;}
 if(b.id==='retry'){begin(true);$('.prepBack').focus({preventScroll:true});return;}
 if(b.id==='replay'){begin();return;}
 if(b.id==='first-frame'){firstFrame();return;}
 if(b.id==='prep-review-toggle'){const open=document.body.classList.toggle('prepReviewOpen');b.setAttribute('aria-expanded',String(open));b.textContent=open?'收起评审控件':'预览状态';return;}
 if(state!=='ready')return;
 const action=b.dataset.readyAction;
 if(action==='toggle'){
  playing=!playing;
  b.setAttribute('aria-label',playing?'暂停':'播放');
  b.querySelector('path').setAttribute('d',playing?'M8 5h3v14H8zM15 5h3v14h-3z':'M8 5L20 12L8 19Z');
 }else if(action==='backward'||action==='forward')position=Math.max(0,Math.min(duration,position+(action==='backward'?-5:15)));
 else if(action)location.href='home-resume.html?screen=player';
 updateTime();autoHide();
});
$('#prep-seek').addEventListener('input',e=>{if(state==='ready'){position=Number(e.target.value);updateTime();autoHide();}});
stage.addEventListener('click',e=>{if(state==='ready'&&!e.target.closest('button,a,input')){const wasHidden=stage.classList.contains('prepControlsHidden');autoHide();if(!wasHidden)stage.classList.add('prepControlsHidden');}});
const progress=setInterval(()=>{if(state==='ready'&&playing){position=Math.min(duration,position+1);updateTime();}},1000);
window.addEventListener('pagehide',()=>{clearTimers();clearInterval(progress);});
render();
})();
