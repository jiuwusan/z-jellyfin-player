(() => {

const catalog={
 detective:{name:"魅影神捕",type:"剧集",year:"2026",art:0,desc:"离奇旧案再现江城，神捕黎斯循着一具傀儡的来历追查，在迷雾里拼起多年未解的真相。"},
 forest:{name:"山野回声",type:"电影",year:"2025",art:1,length:6070,desc:"一位录音师走进久无人迹的山谷，想记录最后一段林间回声，却遇到一位等候多年的陌生人。"},
 neon:{name:"夜港来信",type:"剧集",year:"2026",art:2,desc:"一封寄错的信，让三位素不相识的人在夜港相遇。"},
 lighthouse:{name:"潮汐尽头",type:"电影",year:"2025",art:3,length:6180,desc:"暴风雨来临前，守塔人收到一段来自海上的旧录音。"},
 desert:{name:"穿过赤色山谷",type:"电影",year:"2024",art:4,length:6900,desc:"摄影师在荒漠中寻找失踪的同伴，一路沿着风留下的记号前行。"},
 palace:{name:"长夜未央",type:"剧集",year:"2025",art:5,desc:"宫城入夜之后，一桩无名旧案重新浮出水面。"},
 ep1:{name:"傀儡惊现，黎斯以身入局",type:"单集",parent:"detective",season:1,episode:1,art:0,length:2780,desc:"一具傀儡出现在案发现场，黎斯循线进入深巷。"},
 ep2:{name:"月下疑踪",type:"单集",parent:"detective",season:1,episode:2,art:0,length:2620,desc:"夜访旧巷未得实证，黎斯将目光转向城外。"},
 ep3:{name:"灯下旧案",type:"单集",parent:"detective",season:1,episode:3,art:0,length:2750,desc:"关于那封书信，一位故人知道更多。"},
 ep4:{name:"雨夜归来",type:"单集",parent:"detective",season:2,episode:1,art:0,length:2650,desc:"一场大雨让江城旧案重现。"},
 n2:{name:"雨停之前",type:"单集",parent:"neon",season:1,episode:2,art:2,length:2500,desc:"港口停电前，三人交换了彼此知道的线索。"},
 n3:{name:"来自旧码头的电话",type:"单集",parent:"neon",season:1,episode:3,art:2,length:2450,desc:"一个电话把线索带回废弃的旧码头。"},
 p2:{name:"孤灯与故人",type:"单集",parent:"palace",season:1,episode:2,art:5,length:2550,desc:"归来的访客知道一段未经记载的往事。"}
};
const tiles=["detective","forest","neon","lighthouse","desert","palace"];
const mediaLibraries=[
 {id:"movies",name:"电影",items:["forest","lighthouse"]},
 {id:"series",name:"电视剧",items:["detective","neon","palace"]},
 {id:"favorites",name:"自建片库",items:["desert"]}
];
const latest=["lighthouse","n2","ep4","palace","desert","forest"];
const next=["n3","p2","ep2"];
const records={ep1:1961,forest:1098,lighthouse:314,n2:2500};
// Preview fixture only; no App or server state is changed.
if(document.documentElement.dataset.homePreview==="latest"||new URLSearchParams(location.search).get("resume")==="empty") Object.keys(records).forEach(id=>delete records[id]);
// Jellyfin Web v10.11.11 playback settings use width and bits per second.
const resolutions=[["0","自动"],["-1","屏幕分辨率"],["640","360p"],["852","480p"],
 ["1280","720p"],["1920","1080p"],["3840","4K"],["7680","8K"]];
const bitrates=[["0","自动"],
 ...[120,80,60,40,20,15,10,8,6,4,3,1.5].map(value=>[String(value*1_000_000),value+" Mbps"]),
 ["720000","720 kbps"],["420000","420 kbps"]];
const settingsConfirmation="jellyfin";
const initialSettings={host:"proxy.example.net",port:"8080",proxyUser:"family",proxyPass:"prototype",server:"https://media.example.net/jellyfin",user:"家庭账号",pass:"prototype",resolution:"1920",bitrate:"4000000"};
let saved={...initialSettings};
let pendingSettings=null;
const previewParams=new URLSearchParams(location.search);
if(document.documentElement.dataset.homePreview==="latest") previewParams.set("resume","empty");
const initialScreen=previewParams.get("screen");
const previewItem=previewParams.get("item");
let current=initialScreen==="detail"
 ?{page:"detail",id:tiles.includes(previewItem)?previewItem:"detective"}
 :{page:["library","settings","search","splash"].includes(initialScreen)?initialScreen:"home"};
const stack=[];
let playTime=0, playing=false, timer=null, playerKey="";
const controlsTimeoutMs=5000;
let controlsTimeout, bufferTimeout, buffering=false, playerPointer=null, scrubbing=false;
let playerTracks={audio:0,subtitle:0};
const audioOptions=[["国语","原声 · AAC 2.0"],["粤语","配音 · AAC 2.0"]];
const subtitleOptions=[["自动","使用 Jellyfin 的字幕偏好"],["关闭",""],["简体中文","Jellyfin 外挂 · SRT"],["English","Jellyfin 外挂 · WebVTT"]];
const desktop=document.querySelector(".desktop");
const main=document.querySelector("main"), top=document.querySelector(".top"), bottom=document.querySelector(".bottom");
const settingsDialog=document.querySelector("#settings-dialog");
const confirmationForm=document.querySelector("#confirmation-form");
const confirmation=confirmationForm.elements.confirmation;
const confirmationError=document.querySelector("#confirm-error");
const esc=s=>String(s).replace(/[&<>"']/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));
const largeNavIcons={"home":"<svg viewBox=\"0 0 24 24\" aria-hidden=\"true\"><path d=\"m3 10 9-7 9 7v11h-6v-7H9v7H3Z\"/></svg>","library":"<svg viewBox=\"0 0 24 24\" aria-hidden=\"true\"><rect x=\"4\" y=\"3\" width=\"16\" height=\"18\" rx=\"1\"/><path d=\"M7 8h10M7 12h10M7 16h10\"/></svg>","settings":"<svg viewBox=\"0 0 24 24\" aria-hidden=\"true\"><path d=\"m9 3-.6 2.2-2 .9-2-.6-2 3.5 1.6 1.6v2.5L2.4 15l2 3.5 2.2-.5 1.8 1L9 21h4l.6-2.1 1.9-1 2.1.6 2-3.5-1.6-1.6v-2.5l1.6-1.6-2-3.5-2.1.6-1.9-1L13 3Z\"/><circle cx=\"11\" cy=\"12\" r=\"3\"/></svg>"};
const kind={home:"首页",library:"媒体库",detail:"媒体详情",settings:"连接设置",search:"搜索",player:"正在播放"};
const art=n=>'<span class="art a'+n+'" aria-hidden="true"></span>';
const time=n=>{const secs=Math.max(0,Math.floor(Number(n)||0)), s=String(secs%60).padStart(2,"0"), m=Math.floor(secs/60),h=Math.floor(m/60);return h?h+":"+String(m%60).padStart(2,"0")+":"+s:m+":"+s};
const parentName=i=>i.parent?catalog[i.parent].name:i.name;
const epLabel=i=>i.parent?"第 "+i.season+" 季 · 第 "+i.episode+" 集":"";
function adjacentEpisode(id,offset){
 const parent=catalog[id].parent;
 if(!parent)return;
 const siblings=Object.keys(catalog).filter(key=>catalog[key].parent===parent);
 return siblings[siblings.indexOf(id)+offset];
}
const isWatched=id=>catalog[id].length>0&&records[id]>=catalog[id].length;
const resumePosition=id=>isWatched(id)?0:records[id]||0;
const watchedBadge='<span class="watched"><span aria-hidden="true">✓</span>已看完</span>';
const logo='<img src="../logo.png" alt="" width="38" height="38" class="mark">';
const ratio=id=>Math.min(100,Math.max(0,(records[id]||0)/(catalog[id].length||1)*100));
const progress=id=>'<span class="progress" aria-label="已看 '+Math.round(ratio(id))+'%"><i style="width:'+ratio(id)+'%"></i></span>';
const openButton=(label,data,cls="secondary")=>'<button class="'+cls+'" '+data+'>'+esc(label)+'</button>';
function leavePlayer(){
 if(!playerKey)return;
 if(playTime>0)records[playerKey]=Math.min(playTime,catalog[playerKey].length);
 clearInterval(timer);timer=null;playing=false;playerKey="";
 clearTimeout(controlsTimeout);
 clearTimeout(bufferTimeout);
 buffering=false;playerPointer=null;scrubbing=false;
}
function navigate(page, opts={}, replace=false){
 if(current.page==="player")leavePlayer();
 if(replace){stack.length=0;}else{stack.push({...current,scroll:main.scrollTop});}
 current={page,...opts}; render();
}
function back(){
 if(current.page==="player")leavePlayer();
 current=stack.pop()||{page:"home"};render();main.scrollTop=current.scroll||0;
}
function section(title,markup,action=""){
 return '<section class="group"><div class="section"><h2>'+title+'</h2>'+action+'</div>'+markup+'</section>';
}
function card(id,mode="poster"){
 const i=catalog[id], resume=mode==="resume", episode=Boolean(i.parent);
 const resumeStyle=resume||mode==="fallback";
 const landscape=resumeStyle||mode==="next-up";
 const homeCard=landscape||mode==="recent";
 const name=episode?parentName(i):i.name;
 const year=episode?catalog[i.parent].year:i.year;
 const type=episode?"剧集":i.type;
 const titleMarkup=esc(name)+(resumeStyle&&year?'<span class="cardYear">（'+esc(year)+'）</span>':"");
 const episodeNumber=episode&&homeCard?(i.season>1?"第"+i.season+"季 ":"")+"第"+i.episode+"集":epLabel(i);
 const detail=episode?episodeNumber+(homeCard?"":" · "+i.name):i.type+" · "+i.year;
 const watchTime=resume?time(records[id])+" / "+time(i.length):"";
 const coverInfo=resumeStyle?'<span class="resumeShade" aria-hidden="true"></span>'+
 '<span class="resumeType">'+esc(type)+'</span>'+
 (episode?'<span class="resumeEpisode">'+esc(episodeNumber)+'</span>':"")+
 (resume?'<span class="watchTime">'+watchTime+'</span>'+progress(id):"")+
 (resumeStyle?'<button class="quickPlayHit" type="button" data-home-play="'+id+'" aria-label="'+esc((resume?"继续观看 ":"播放 ")+name)+'">'+'<svg class="quickPlayIcon" viewBox="0 0 34 34" aria-hidden="true"><path d="M12.5,9.5 L26,17 L12.5,24.5 Z"/></svg></button>':""): "";
 const content='<span class="cover '+(landscape?"landscape":"")+'">'+art(i.art)+coverInfo+'</span>'+
 '<span class="cardCopy"><span class="name">'+titleMarkup+'</span>'+
 (resumeStyle||landscape&&episode?"":'<span class="sub">'+esc(detail)+'</span>')+'</span>';
 if(resumeStyle)return '<div class="card homeCard">'+content+
 '<button class="homeCardDetails" type="button" data-home-details="'+id+'" aria-label="'+esc("查看详情 "+name+"，"+detail)+'"></button></div>';
 return '<button class="card" type="button" data-open-item="'+id+'">'+content+'</button>';
}
function rail(ids,mode){
 return '<div class="rail '+(mode==="fallback"?"resume fallback":mode||"poster")+'" tabindex="0">'+ids.map(id=>card(id,mode)).join("")+'</div>';
}
function homeCards(ids,mode){
 return '<div class="homeStack resume '+(mode==="fallback"?"fallback":"")+'">'+ids.map(id=>card(id,mode)).join("")+'</div>';
}
function home(){
 const resumed=Object.keys(records).filter(id=>records[id]>0&&records[id]<catalog[id].length);
 return resumed.length?section("继续观看",homeCards(resumed,"resume"))
 :section("最近添加",latest.length?homeCards(latest,"fallback"):'<div class="empty">暂无可播放的影片。</div>');
}
function library(){
 const selected=current.filter||"all", libraries=[{id:"all",name:"全部",items:tiles},...mediaLibraries];
 const matches=libraries.find(library=>library.id===selected).items;
 return '<div class="filters" aria-label="媒体库">'+libraries.map(({id,name})=>'<button class="chip" data-filter="'+id+'" aria-pressed="'+(id===selected)+'">'+esc(name)+'</button>').join("")+
 '</div><div class="grid">'+matches.map(id=>card(id)).join("")+'</div>';
}
function detail(){
 const id=current.id,i=catalog[id],watched=isWatched(id),resume=resumePosition(id);
 const playButton=(label,target,position)=>'<button class="detailPlay" data-play="'+target+'" data-position="'+position+'">'+
  '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M8 5 L19 12 L8 19 Z"/></svg><span>'+esc(label)+'</span></button>';
 const meta=i.year+" · "+i.type+(i.length?" · "+Math.round(i.length/60)+" 分钟":"");
 let buttons="", episodes="";
 if(i.type==="剧集"){
  const ids=Object.keys(catalog).filter(k=>catalog[k].parent===id), seasons=[...new Set(ids.map(k=>catalog[k].season))];
  const target=ids.find(k=>resumePosition(k)>0)||ids.find(k=>!isWatched(k))||ids[0];
  if(target){
   const position=resumePosition(target);
   buttons='<div class="actions">'+playButton(isWatched(target)?"重播":position?"继续观看":"播放",target,position)+
      (position?openButton("从头播放",'data-play="'+target+'" data-position="0"'):"")+'</div>';
  }
  const sel=current.season||seasons[0];
  const list=ids.filter(k=>catalog[k].season===sel).map(k=>{
   const episode=catalog[k], done=isWatched(k);
   return '<button class="episode" data-open-item="'+k+'"><span class="cover landscape">'+art(episode.art)+'</span>'+
    '<span class="episodeInfo"><span class="name">第 '+episode.episode+' 集 · '+episode.name+'</span>'+
    '<span class="sub">'+Math.round(episode.length/60)+' 分钟'+(records[k]&&!done?' · 已看到 '+time(records[k]):"")+'</span></span>'+
    (done?watchedBadge:"")+'</button>';
  }).join("");
  const tabs=seasons.length>1?'<div class="seasonTabs" role="tablist" aria-label="选择季">'+
   seasons.map(n=>'<button class="chip" id="season-tab-'+n+'" role="tab" data-season="'+n+'" aria-controls="episodes-panel" aria-selected="'+(n===sel)+'" tabindex="'+(n===sel?0:-1)+'">第 '+n+' 季</button>').join("")+
   '</div>':"";
  episodes=section("选集",tabs+(tabs?'<div id="episodes-panel" role="tabpanel" aria-labelledby="season-tab-'+sel+'">'+list+'</div>':list));
 }else{
  buttons='<div class="actions">'+playButton(watched?"重播":resume?"继续观看":"播放",id,resume)+
      (resume?openButton("从头播放",'data-play="'+id+'" data-position="0"'):"")+'</div>';
 }
 return '<div class="detail largeDetail"><div class="heroCover">'+art(i.art)+'<span class="shade" aria-hidden="true"></span></div>'+
 '<div class="detailRibbon"><div class="detailIdentity"><div class="cover detailPoster">'+art(i.art)+'</div>'+
 '<div class="detailHeading"><h1>'+esc(i.name)+'</h1><p class="meta">'+esc(meta)+(watched?watchedBadge:"")+'</p></div></div>'+buttons+
 (resume?'<div class="watchprogress">'+progress(id)+'</div><small>已观看 '+time(resume)+' / '+time(i.length)+'</small>':"")+
 '</div><div class="sheet"><p class="summary">'+esc(i.desc)+'</p>'+episodes+'</div></div>';
}
function settings(){
 const fields=(name,key,type="text")=>'<label class="field">'+name+'<input name="'+key+'" type="'+type+'" value="'+esc(saved[key])+'" required autocomplete="off"></label>';
 const choices=(name,key,values)=>'<label class="field">'+name+'<select name="'+key+'">'+
 values.map(([value,label])=>'<option value="'+value+'" '+(value===saved[key]?"selected":"")+'>'+label+'</option>').join("")+'</select></label>';
 return '<div class="settings"><form id="settings"><section class="settingsGroup"><h2>家庭 HTTP 代理</h2><div class="columns">'+fields("代理地址","host")+fields("端口","port","number")+
 '</div>'+fields("代理用户名","proxyUser")+fields("代理密码","proxyPass","password")+'</section>'+
 '<section class="settingsGroup"><h2>Jellyfin 授权</h2>'+fields("服务器地址","server","url")+fields("Jellyfin 账号","user")+fields("Jellyfin 密码","pass","password")+'</section>'+
 '<section class="settingsGroup"><h2>播放画质</h2><div class="qualityFields">'+choices("分辨率上限","resolution",resolutions)+
 choices("码率上限（音视频合计）","bitrate",bitrates)+'</div></section>'+
 '<div class="settingsFooter"><button class="primary full" type="submit">保存并连接</button><div class="helper" id="settings-message" aria-live="polite"></div></div></form></div>';
}
function searchResults(term){
 const q=term.trim().toLowerCase();
 const ids=tiles.filter(id=>!q||catalog[id].name.toLowerCase().includes(q));
 return ids.length?'<div class="grid">'+ids.map(id=>card(id)).join("")+'</div>':'<p class="empty">没有找到匹配的影片。</p>';
}
function player(){
 const i=catalog[current.id];
 const resolution=resolutions.find(([value])=>value===saved.resolution)[1];
 const bitrate=bitrates.find(([value])=>value===saved.bitrate)[1];
 const backLabel=stack.at(-1)?.page==="home"?"返回首页":"返回详情";
 const episodeButton=(target,label,symbol)=>'<button class="transportButton episodeNav" type="button" data-episode="'+
  (target||"")+'" '+(target?"":"disabled")+'><span aria-hidden="true">'+symbol+'</span>'+label+'</button>';
 return '<section class="theater" aria-label="全屏播放器">'+
  '<div class="playerScene">'+art(i.art)+'</div><button class="playerSurface" type="button" data-toggle-controls aria-label="隐藏播放控制" aria-expanded="true" aria-controls="player-controls"></button>'+
  '<div class="playerControls" id="player-controls">'+
   '<div class="playerHeader"><button class="playerBack" type="button" data-back aria-label="'+backLabel+'" title="'+backLabel+'"><span aria-hidden="true"></span></button>'+
    '<div class="playerHeading"><h2>'+esc(parentName(i))+'</h2>'+
    (i.parent?'<p>'+esc(epLabel(i)+" · "+i.name)+'</p>':'<p>'+esc(i.year+" · "+i.type)+'</p>')+'</div>'+
    '<div class="playerTools"><button type="button" data-track-menu="subtitle" aria-expanded="false" aria-controls="track-menu"><span class="captionIcon" aria-hidden="true">CC</span>字幕</button>'+
    '<button type="button" data-track-menu="audio" aria-expanded="false" aria-controls="track-menu"><span class="audioIcon" aria-hidden="true">♪</span>音轨</button></div></div>'+
   '<div class="playerBottom"><input type="range" id="seek" min="0" max="'+i.length+'" value="'+playTime+'" aria-label="播放进度">'+
    '<div class="playerToolbar"><span class="playerTime" id="times">'+time(playTime)+' / '+time(i.length)+'</span>'+
    '<div class="transport">'+(i.parent?episodeButton(adjacentEpisode(current.id,-1),"上一集","‹"):"")+
    '<button class="transportButton seekButton" type="button" data-offset="-5" aria-label="后退 5 秒">−5<span aria-hidden="true">秒</span></button>'+
    '<button class="playerToggle" type="button" data-toggle-player aria-label="'+(playing?"暂停":"播放")+'" data-playing="'+playing+'"><span aria-hidden="true"></span></button>'+
    '<button class="transportButton seekButton" type="button" data-offset="15" aria-label="前进 15 秒">+15<span aria-hidden="true">秒</span></button>'+
    (i.parent?episodeButton(adjacentEpisode(current.id,1),"下一集","›"):"")+'</div>'+
    '<span class="playerQuality" title="播放画质上限">'+esc(resolution+" · "+bitrate)+'</span></div></div></div>'+
  '<div class="playerCaption" aria-hidden="true" hidden><span></span></div>'+
  '<div class="playerBuffering" role="status" hidden>正在缓冲…</div>'+
  '<section class="trackMenu" id="track-menu" role="dialog" aria-labelledby="track-title" hidden></section></section>';
}
function splash(){
 return '<button class="splashStage" data-tab="home" type="button" aria-label="结束开屏预览并进入首页">'+
 '<span class="splashIdentity"><img src="../logo.png" width="140" height="140" alt="">'+
 '<strong>z-jellyfin</strong><span>家庭影音</span></span></button>';
}
function render(){
 const {page}=current, onHome=page==="home";
 desktop.dataset.screen=page;
 document.querySelector(".shell").dataset.screen=page;
 main.dataset.screen=page;
 top.innerHTML=page==="splash"?"":'<div class="brand">'+(onHome?logo+'z-jellyfin':'<button class="icon largeTool" data-back aria-label="返回"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="m15 5-7 7 7 7"/></svg><span>返回</span></button><span class="toplabel">'+esc(kind[page])+'</span>')+'</div>'+
 (page!=="player"&&page!=="settings"?'<div class="tools">'+(page==="search"?"":'<button class="icon largeTool" data-go="search" aria-label="搜索"><svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="10.5" cy="10.5" r="6.5"/><path d="m15.5 15.5 5 5"/></svg><span>搜索</span></button>')+'<button class="icon largeTool" data-go="settings" aria-label="连接设置"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="m9 3-.6 2.2-2 .9-2-.6-2 3.5 1.6 1.6v2.5L2.4 15l2 3.5 2.2-.5 1.8 1L9 21h4l.6-2.1 1.9-1 2.1.6 2-3.5-1.6-1.6v-2.5l1.6-1.6-2-3.5-2.1.6-1.9-1L13 3Z"/><circle cx="11" cy="12" r="3"/></svg><span>设置</span></button></div>':"");
 const tabs=["home","library","settings"];
 const tab=tabs.includes(page)?page:stack.findLast(entry=>tabs.includes(entry.page))?.page||"home";
 bottom.innerHTML=[["home","⌂","首页"],["library","▤","媒体库"],["settings","⚙","设置"]].map(([p,icon,name])=>'<button data-tab="'+p+'" aria-current="'+(p===tab?"page":"false")+'"><span aria-hidden="true">'+largeNavIcons[p]+'</span>'+name+'</button>').join("");
 const contents={home,library,detail,settings,search:()=>'<div class="screenSearch"><input class="searchField" id="search-input" type="search" placeholder="搜索片名" aria-label="搜索片名" value="'+esc(current.query||"")+'"></div><div id="results">'+searchResults(current.query||"")+'</div>',player,splash};
 main.className=page==="detail"||page==="splash"||page==="player"?"":"pad";
 main.innerHTML=contents[page]();
 if(page==="library")main.querySelector(".filters").scrollLeft=current.filterScroll||0;
 main.scrollTop=0;
 document.querySelectorAll(".jump[data-home-preview]").forEach(link=>{
  const example=previewParams.get("resume")==="empty"?"latest":"resume";
  link.setAttribute("aria-current",page==="home"&&link.dataset.homePreview===example?"page":"false");
 });
 document.querySelectorAll("[data-jump]").forEach(button=>{
  const selected=button.dataset.jump===page&&(page!=="detail"||current.id==="detective");
  button.setAttribute("aria-current",selected?"page":"false");
 });
}
function showPlayer(id,position,replaceEpisode=false){
 const keepSubtitlesOff=replaceEpisode&&playerTracks.subtitle===1;
 const continuePlaying=!replaceEpisode||playing;
 if(replaceEpisode)leavePlayer();
 else stack.push({...current,scroll:main.scrollTop});
 current={page:"player",id};
 playerKey=id;playTime=position;playing=continuePlaying;
 playerTracks={audio:0,subtitle:keepSubtitlesOff?1:0};
 render();
 updateTime();
 updateCaption();
 if(playing)simulateBuffering();
 else restartControlsTimeout();
 timer=setInterval(()=>{
  if(!playing||!playerKey||buffering||scrubbing)return;
  const max=catalog[playerKey].length;
  playTime=Math.min(playTime+1,max);
  if(playTime===max){
   const nextEpisode=adjacentEpisode(playerKey,1);
   if(nextEpisode){showPlayer(nextEpisode,isWatched(nextEpisode)?0:records[nextEpisode]||0,true);return;}
   setPlaying(false);
  }
  updateTime();
 },1000);
}
function updateTime(){
 const times=document.querySelector("#times"), seek=document.querySelector("#seek");
 if(times&&seek&&playerKey){
  times.textContent=time(playTime)+" / "+time(catalog[playerKey].length);seek.value=playTime;
  seek.style.setProperty("--seek-progress",playTime/catalog[playerKey].length*100+"%");
  seek.setAttribute("aria-valuetext",time(playTime)+" / "+time(catalog[playerKey].length));
 }
}
function setPlaying(value){
 playing=value;
 const button=main.querySelector("[data-toggle-player]");
 button.dataset.playing=String(playing);
 button.setAttribute("aria-label",playing?"暂停":"播放");
 restartControlsTimeout();
}
function seekTo(position){
 const duration=catalog[playerKey].length;
 playTime=Math.min(duration,Math.max(0,position));
 if(playTime===duration)setPlaying(false);
 updateTime();
 restartControlsTimeout();
}
function controlsMustStayVisible(){
 return !playing||buffering||!main.querySelector(".trackMenu").hidden;
}
function describeTouchSurface(){
 const button=main.querySelector(".playerSurface"), controls=main.querySelector(".playerControls");
 const label=controls.hidden?"显示播放控制":!main.querySelector(".trackMenu").hidden?"收起轨道菜单":
  controlsMustStayVisible()?"播放画面":"隐藏播放控制";
 button.setAttribute("aria-expanded",String(!controls.hidden));
 button.setAttribute("aria-label",label);
}
function restartControlsTimeout(){
 clearTimeout(controlsTimeout);
 if(!playerKey)return;
 const controls=main.querySelector(".playerControls");
 if(controlsMustStayVisible())controls.hidden=false;
 else if(playerPointer===null&&!controls.hidden){
  controlsTimeout=setTimeout(()=>{
   if(controlsMustStayVisible()||playerPointer!==null)return;
   controls.hidden=true;
   describeTouchSurface();
  },controlsTimeoutMs);
 }
 describeTouchSurface();
}
function simulateBuffering(){
 clearTimeout(bufferTimeout);
 const indicator=main.querySelector(".playerBuffering");
 indicator.hidden=false;
 buffering=true;
 restartControlsTimeout();
 bufferTimeout=setTimeout(()=>{
  indicator.hidden=true;
  buffering=false;
  restartControlsTimeout();
 },400);
}
function updateCaption(){
 const caption=main.querySelector(".playerCaption");
 caption.hidden=playerTracks.subtitle!==2&&playerTracks.subtitle!==3;
 caption.firstElementChild.textContent=playerTracks.subtitle===3?"Someone has been here.":"这里有人来过。";
}
function closeTracks(restoreFocus=true){
 const menu=main.querySelector(".trackMenu");
 if(!menu||menu.hidden)return false;
 menu.hidden=true;
 const trigger=main.querySelector('[data-track-menu][aria-expanded="true"]');
 if(trigger){trigger.setAttribute("aria-expanded","false");if(restoreFocus)trigger.focus();}
 restartControlsTimeout();
 return true;
}
function showTracks(type){
 const menu=main.querySelector(".trackMenu");
 if(!menu.hidden&&menu.dataset.track===type){closeTracks();return;}
 closeTracks(false);
 menu.dataset.track=type;
 const options=type==="subtitle"?subtitleOptions:audioOptions;
 menu.innerHTML='<div class="trackHeader"><h3 id="track-title">'+(type==="subtitle"?"字幕":"音轨")+
  '</h3><button type="button" data-close-tracks aria-label="关闭菜单">×</button></div><div class="trackOptions">'+
  options.map(([label,detail],i)=>'<button type="button" data-track-choice="'+i+'" aria-pressed="'+
   (i===playerTracks[type])+'"><span><strong>'+esc(label)+'</strong>'+(detail?'<small>'+esc(detail)+'</small>':"")+
   '</span><span class="trackCheck" aria-hidden="true">✓</span></button>').join("")+'</div>';
 menu.hidden=false;
 main.querySelector('[data-track-menu="'+type+'"]').setAttribute("aria-expanded","true");
 menu.querySelector('[aria-pressed="true"]').focus();
 restartControlsTimeout();
}
function togglePlayerControls(){
 if(closeTracks()||controlsMustStayVisible())return;
 const controls=main.querySelector(".playerControls");
 controls.hidden=!controls.hidden;
 restartControlsTimeout();
}
function switchSeason(tab){
 const seasons=tab.closest(".seasonTabs");
 const scroll=main.scrollTop, left=seasons.scrollLeft;
 current.season=Number(tab.dataset.season);
 render();
 main.scrollTop=scroll;
 const selected=main.querySelector('[data-season="'+current.season+'"]');
 const row=selected.closest(".seasonTabs");
 row.scrollLeft=left;
 const bounds=row.getBoundingClientRect(), item=selected.getBoundingClientRect();
 if(item.left<bounds.left)row.scrollLeft+=item.left-bounds.left-4;
 if(item.right>bounds.right)row.scrollLeft+=item.right-bounds.right+4;
 selected.focus({preventScroll:true});
}
document.addEventListener("click",e=>{
 const button=e.target.closest("button");if(!button)return;
 const d=button.dataset;
 if("back" in d)back();
 else if(d.jump==="player"){navigate("home",{},true);showPlayer("ep1",records.ep1||0);}
 else if(d.jump)navigate(d.jump,d.jump==="detail"?{id:"detective"}:{},true);
 else if(d.tab)navigate(d.tab,{},true);
 else if(d.go)navigate(d.go);
 else if(d.filter){current.filter=d.filter;current.filterScroll=button.closest(".filters").scrollLeft;render();}
 else if(d.season&&button.getAttribute("aria-selected")!=="true")switchSeason(button);
 else if(d.homeDetails){
  const id=d.homeDetails; navigate("detail",{id:catalog[id].parent||id});
 }
 else if(d.homePlay){
  const id=d.homePlay, i=catalog[id];
  const episodes=Object.keys(catalog).filter(key=>catalog[key].parent===id);
  const target=i.type==="剧集"?episodes.find(key=>resumePosition(key)>0)||episodes.find(key=>!isWatched(key))||episodes[0]:id;
  if(target)showPlayer(target,resumePosition(target));
 }
 else if(d.openItem){
  const id=d.openItem;
  if(catalog[id].parent)showPlayer(id,resumePosition(id));
  else navigate("detail",{id});
 }
 else if(d.play)showPlayer(d.play,Number(d.position));
 else if(d.episode)showPlayer(d.episode,isWatched(d.episode)?0:records[d.episode]||0,true);
 else if("togglePlayer" in d){if(!playing&&playTime===catalog[playerKey].length){playTime=0;updateTime();}setPlaying(!playing);}
 else if("offset" in d)seekTo(playTime+Number(d.offset));
 else if(d.trackMenu)showTracks(d.trackMenu);
 else if("closeTracks" in d)closeTracks();
 else if("trackChoice" in d){
  const type=button.closest(".trackMenu").dataset.track, choice=Number(d.trackChoice);
  const audioChanged=type==="audio"&&choice!==playerTracks.audio;
  playerTracks[type]=choice;updateCaption();closeTracks();
  if(audioChanged)simulateBuffering();
 }
 else if("toggleControls" in d)togglePlayerControls();
});
document.addEventListener("pointerdown",e=>{
 if(!playerKey||playerPointer!==null||!e.target.closest(".theater"))return;
 playerPointer=e.pointerId;
 scrubbing=e.target.id==="seek";
 restartControlsTimeout();
});
function endPlayerGesture(e){
 if(playerPointer!==e.pointerId)return;
 playerPointer=null;scrubbing=false;
 restartControlsTimeout();
}
document.addEventListener("pointerup",endPlayerGesture);
document.addEventListener("pointercancel",endPlayerGesture);
document.addEventListener("input",e=>{
 if(e.target.id==="search-input"){
  current.query=e.target.value;
  document.querySelector("#results").innerHTML=searchResults(current.query);
 }
 if(e.target.id==="seek"&&playerKey)seekTo(Number(e.target.value));
});
document.addEventListener("submit",e=>{
 if(e.target.id!=="settings")return;
 e.preventDefault();
 const data=Object.fromEntries(new FormData(e.target));
 const helper=e.target.querySelector(".helper");
 if(Number(data.port)<1||Number(data.port)>65535){helper.textContent="请输入 1 到 65535 之间的代理端口。";return;}
 pendingSettings=data;
 const shell=document.querySelector(".shell").getBoundingClientRect();
 settingsDialog.style.setProperty("--dialog-center",shell.left+shell.width/2+"px");
 settingsDialog.showModal();
 confirmation.focus();
});
confirmationForm.addEventListener("submit",e=>{
 e.preventDefault();
 if(!pendingSettings)return;
 if(confirmation.value!==settingsConfirmation){
  confirmationError.textContent=confirmation.value?"确认密码错误，请重新输入。":"请输入确认密码。";
  confirmation.setAttribute("aria-invalid","true");
  confirmation.focus();
  return;
 }
 saved=pendingSettings;
 document.querySelector("#settings-message").textContent="示例设置已保存。";
 settingsDialog.close();
});
document.querySelector("#cancel-settings").addEventListener("click",()=>settingsDialog.close());
settingsDialog.addEventListener("close",()=>{
 pendingSettings=null;
 confirmation.value="";
 confirmation.removeAttribute("aria-invalid");
 confirmationError.textContent="";
});
document.addEventListener("keydown",e=>{
 if(settingsDialog.open)return;
 if(e.key==="Escape"&&stack.length){if(!closeTracks())back();return;}
 if(playerKey&&e.target instanceof Element&&e.target.closest(".theater"))restartControlsTimeout();
 if(!e.target.matches("[data-season]"))return;
 const tabs=[...e.target.closest(".seasonTabs").querySelectorAll("[data-season]")];
 const index=tabs.indexOf(e.target);
 const step={ArrowLeft:(index-1+tabs.length)%tabs.length,ArrowRight:(index+1)%tabs.length,Home:0,End:tabs.length-1};
 if(Object.hasOwn(step,e.key)){e.preventDefault();switchSeason(tabs[step[e.key]]);}
});
if(initialScreen==="player")showPlayer("ep1",records.ep1);
else render();
})();
