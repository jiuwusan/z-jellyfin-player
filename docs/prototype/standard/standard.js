(() => {
  'use strict';
  const $ = selector => document.querySelector(selector);
  const phone = $('.phone'), content = $('#content'), topbar = $('.topbar'), tabbar = $('.tabbar'), sheet = $('#sheet');
  const icon = (name, cls = '') => `<svg class="${cls}" aria-hidden="true"><use href="#${name}"></use></svg>`;
  const escape = text => String(text).replace(/[&<>"']/g, char => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[char]));
  const art = n => `<span class="art a${n}" aria-hidden="true"></span>`;
  const time = seconds => { seconds = Math.floor(Math.max(0, seconds)); return seconds >= 3600 ? `${Math.floor(seconds / 3600)}:${String(Math.floor(seconds / 60) % 60).padStart(2,'0')}:${String(seconds % 60).padStart(2,'0')}` : `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2,'0')}`; };
  const media = {
    detective:{name:'魅影神捕',type:'series',year:2026,art:0,description:'离奇旧案再现江城，神捕黎斯循着一具傀儡的来历追查，在迷雾里拼起多年未解的真相。'},
    neon:{name:'夜港来信',type:'series',year:2026,art:2,description:'一封寄错的信，让三位素不相识的人在夜港相遇。'},
    palace:{name:'长夜未央',type:'series',year:2025,art:5,description:'宫城入夜之后，一桩无名旧案重新浮出水面。'},
    mountains:{name:'山海之间',type:'series',year:2025,art:1,description:'穿过山林与河谷，记录人与自然的日常。'},
    coast:{name:'海岸巡礼',type:'series',year:2024,art:3,description:'沿着海岸线，走进潮汐与灯塔的故事。'},
    wild:{name:'荒野来客',type:'series',year:2024,art:4,description:'穿行荒漠的旅人，在远方重新认识自己。'},
    forest:{name:'山野回声',type:'movie',year:2025,art:1,duration:6070,description:'一位录音师走进久无人迹的山谷，想记录最后一段林间回声，却遇到一位等候多年的陌生人。'},
    lighthouse:{name:'潮汐尽头',type:'movie',year:2025,art:3,duration:6180,description:'暴风雨来临前，守塔人收到一段来自海上的旧录音。'},
    desert:{name:'穿过赤色山谷',type:'movie',year:2024,art:4,duration:6900,description:'摄影师在荒漠中寻找失踪的同伴，一路沿着风留下的记号前行。'},
    return:{name:'万里归程',type:'movie',year:2024,art:0,duration:6420,description:'走过遥远的路之后，归来的人重新踏入熟悉的街巷。'},
    letter:{name:'最后一封信',type:'movie',year:2025,art:2,duration:5880,description:'一封没有署名的旧信，带回一段久远的记忆。'},
    south:{name:'远山以南',type:'movie',year:2025,art:5,duration:6600,description:'旅人离开小城，沿着山路寻找往日的约定。'},
    journey:{name:'边境纪行',type:'collection',year:2024,art:4,members:['desert','forest','lighthouse'],description:'三段关于远行与归来的故事，按系列顺序继续旅程。'},
    ep1:{name:'傀儡惊现',type:'episode',parent:'detective',season:1,episode:1,art:0,duration:2780},
    ep2:{name:'月下疑踪',type:'episode',parent:'detective',season:1,episode:2,art:0,duration:2620},
    ep3:{name:'灯下旧案',type:'episode',parent:'detective',season:1,episode:3,art:0,duration:2750},
    ep4:{name:'雨夜归来',type:'episode',parent:'detective',season:2,episode:1,art:0,duration:2650},
    ep5:{name:'迷雾深处',type:'episode',parent:'detective',season:2,episode:2,art:0,duration:2800},
    ep6:{name:'江城往事',type:'episode',parent:'detective',season:2,episode:3,art:0,duration:2690},
    n1:{name:'夜港相遇',type:'episode',parent:'neon',season:1,episode:1,art:2,duration:2500},
    n2:{name:'雨停之前',type:'episode',parent:'neon',season:1,episode:2,art:2,duration:2500},
    n3:{name:'旧码头的电话',type:'episode',parent:'neon',season:1,episode:3,art:2,duration:2450},
    p1:{name:'城门初开',type:'episode',parent:'palace',season:1,episode:1,art:5,duration:2550},
    p2:{name:'孤灯与故人',type:'episode',parent:'palace',season:1,episode:2,art:5,duration:2550}
  };
  const libraries = [{id:'series',name:'电视剧',items:['detective','neon','palace','mountains','coast','wild']}, {id:'movies',name:'电影',items:['forest','lighthouse','desert','letter','return','south']}, {id:'collections',name:'电影系列',items:['journey']}];
  const records = {ep1:1961,forest:1098,lighthouse:314,n2:2500,desert:6900};
  const latest = ['lighthouse','ep4','n3','palace','desert','forest'];
  const preferences = {audio:'Chinese',subtitle:'Chinese',defaultAudio:false};
  let paused = false, menu = '', subtitle = '简体中文 · SRT', audio = '国语 · AAC 2.0';
  let playerId = '', position = 0, ticker, hideTimer, toastTimer, preparing = false, scrubbing = false;
  const initial = () => { const p = new URLSearchParams(location.search); return {page:p.get('screen') || 'home',item:p.get('item') || 'detective',library:p.get('library') || 'series',feed:p.get('feed') || 'resume',season:Number(p.get('season')) || 1,collection:p.get('collection') || '',query:p.get('q') || '',tab:p.get('tab') || 'subscription',scroll:0}; };
  let state = initial();
  if(state.feed === 'latest') Object.keys(records).forEach(id => delete records[id]);
  const titleOf = id => media[media[id].parent || id].name;
  const watched = id => media[id].duration > 0 && (records[id] || 0) >= media[id].duration;
  const resume = id => watched(id) ? 0 : records[id] || 0;
  const episodeLabel = item => `${item.season > 1 ? `第 ${item.season} 季 · ` : ''}第 ${item.episode} 集`;
  const typeName = type => ({movie:'电影',series:'剧集',episode:'剧集',collection:'合集'}[type]);
  const episodesOf = id => Object.keys(media).filter(key => media[key].parent === id);
  const episodeTarget = id => { const ids = episodesOf(id); return ids.find(key => resume(key) > 0) || ids.find(key => !watched(key)) || ids[0]; };
  const collectionTarget = id => media[id].members.find(key => !watched(key)) || media[id].members[0];
  const progress = id => `<span class="progress"><i style="width:${Math.min(100,100 * resume(id) / media[id].duration)}%"></i></span>`;
  const watchedMark = `<span class="watched" aria-label="已看完">${icon('check')}</span>`;
  function toast(text) { clearTimeout(toastTimer); $('.toast').textContent = text; $('.toast').hidden = false; toastTimer = setTimeout(() => $('.toast').hidden = true, 2600); }
  function placeSheet() { const bounds=phone.getBoundingClientRect(); sheet.style.setProperty('--dialog-x',`${bounds.x+bounds.width/2}px`); sheet.style.setProperty('--dialog-y',`${bounds.y+bounds.height/2}px`); }
  function openSheet() { placeSheet(); sheet.showModal(); }
  function savePosition() { if(playerId && position > 0) records[playerId] = Math.min(position,media[playerId].duration); clearInterval(ticker); clearTimeout(hideTimer); }
  function urlFor(value) { const p = new URLSearchParams({screen:value.page}); if(value.item) p.set('item',value.item); if(value.feed === 'latest') p.set('feed','latest'); if(value.page === 'library') p.set('library',value.library); if(value.page === 'detail') p.set('season',value.season); if(value.page === 'settings') p.set('tab',value.tab); if(value.collection) p.set('collection',value.collection); return '?' + p; }
  function go(next, replace = false) {
    const depth = (state.depth || 0) + (replace ? 0 : 1);
    history.replaceState({...state,scroll:content.scrollTop},'',location.href);
    savePosition(); state = {...initial(),...next,depth,scroll:next.scroll || 0}; menu = ''; playerId = '';
    history[replace ? 'replaceState' : 'pushState'](state,'',urlFor(state)); render();
  }
  function back() { if((state.depth || 0) > 0) history.back(); else go({page:'home'},true); }
  function markInside() { history.replaceState({...state,inside:true,scroll:content.scrollTop},'',location.href); }
  function play(id, start, collection = '') {
    if(!media[id]) return toast('此条目仅用于展示海报样式');
    if(media[id].type === 'series') { id = episodeTarget(id); if(!id) return toast('暂无可播放的单集'); }
    if(media[id].type === 'collection') return play(collectionTarget(id),undefined,id);
    markInside(); go({page:'player',item:id,collection,start:start ?? resume(id),feed:state.feed});
  }
  function details(id) { const item = media[id]; markInside(); go({page:item.type === 'collection' ? 'collection' : 'detail',item:item.parent || id,library:state.library,feed:state.feed}); }
  function resumeCard(id, fallback) {
    const item = media[id], parent = media[item.parent || id];
    return `<article class="resume-card ${fallback ? 'fallback' : ''}"><span class="cover">${art(parent.art)}<span class="shade"></span><span class="type-badge">${typeName(item.type)}</span>${item.type === 'episode' ? `<span class="episode-badge">${episodeLabel(item)}</span>` : ''}${fallback ? '' : `<span class="watch-time">${time(resume(id))} / ${time(item.duration)}</span>${progress(id)}`}<button class="quick-play" data-play="${id}" aria-label="${fallback ? '播放' : '继续观看'} ${titleOf(id)}"><span>${icon('play')}</span></button></span><span class="resume-title">${escape(titleOf(id))}<span class="year">（${parent.year}）</span></span><button class="card-details" data-details="${id}" aria-label="查看 ${titleOf(id)} 详情"></button></article>`;
  }
  function posterCard(id, number = 0) {
    const item = media[id];
    return `<button class="poster-card" data-details="${id}" aria-label="查看 ${item.name} 详情"><span class="cover">${art(item.art)}${watched(id) ? watchedMark : ''}${item.type === 'collection' ? `<span class="collection-badge">${item.members.length} 部</span>` : ''}${number ? `<span class="episode-number">${String(number).padStart(2,'0')}</span>` : ''}</span><span class="poster-title">${escape(item.name)}</span><span class="poster-meta">${typeName(item.type)} · ${item.year}</span></button>`;
  }
  function home() {
    const ids = Object.keys(records).filter(id => resume(id) > 0), fallback = ids.length === 0;
    const feed = fallback ? latest : ids;
    return `<div class="page-pad"><div class="section-heading"><h2>${fallback ? '最近添加' : '继续观看'}</h2><small>${feed.length} 项</small></div><div class="home-stack">${feed.map(id => resumeCard(id,fallback)).join('')}</div></div>`;
  }
  function library() {
    const selected = libraries.find(item => item.id === state.library) || libraries[0];
    return `<div class="page-pad library-page"><div class="library-tabs" aria-label="选择媒体库">${libraries.map(item => `<button class="${selected.id === item.id ? 'active' : ''}" data-library="${item.id}" aria-pressed="${selected.id === item.id}">${item.name}</button>`).join('')}</div><div class="library-intro"><span>${selected.items.length} ${selected.id === 'collections' ? '个系列' : '部作品'}</span></div><div class="poster-grid">${selected.items.map(id => posterCard(id)).join('')}</div></div>`;
  }
  function detail(collection = false) {
    const id = collection ? 'journey' : state.item, item = media[id] || media.detective;
    const target = item.type === 'series' ? episodeTarget(id) : item.type === 'collection' ? collectionTarget(id) : id;
    const saved = target ? resume(target) : 0;
    const primary = target ? `<button class="play-primary" data-play="${target}" ${collection ? 'data-queue="journey"' : ''}>${icon('play')}<span>${saved ? '继续观看' : '播放'}</span></button>` : '';
    const again = saved ? `<button class="button-secondary" data-play="${target}" data-start="0" ${collection ? 'data-queue="journey"' : ''}>从头播放</button>` : '';
    let inner = '';
    if(item.type === 'series') {
      const ids = episodesOf(id), seasons = [...new Set(ids.map(key => media[key].season))];
      const season = seasons.includes(state.season) ? state.season : seasons[0];
      inner = `<div class="subheading"><h2>选集</h2><small>共 ${ids.length} 集</small></div><div class="season-tabs">${seasons.map(number => `<button class="${number === season ? 'active' : ''}" data-season="${number}" aria-pressed="${number === season}">第 ${number} 季</button>`).join('')}</div><div class="episode-list">${ids.filter(key => media[key].season === season).map(key => {
        const episode = media[key];
        return `<button class="episode-row" data-play="${key}"><span class="cover">${art(item.art)}${watched(key) ? watchedMark : ''}</span><span style="min-width:0"><h3>第 ${episode.episode} 集 · ${episode.name}</h3><p>${Math.round(episode.duration / 60)} 分钟${watched(key) ? ' · 已看完' : resume(key) ? ` · <span class="resume-text">播放至 ${time(resume(key))}</span>` : ''}</p></span>${icon('play')}</button>`;
      }).join('')}</div>`;
      if(!ids.length) inner = '<div class="empty">暂无可播放的单集</div>';
    } else if(item.type === 'collection') inner = `<div class="subheading"><h2>系列影片</h2><small>按合集顺序</small></div><div class="poster-grid">${item.members.map((key,index) => posterCard(key,index+1)).join('')}</div>`;
    return `<div class="detail-page"><div class="detail-hero">${art(item.art)}</div><div class="detail-summary"><span class="cover detail-poster">${art(item.art)}</span><div><h1>${escape(item.name)}</h1><p class="detail-meta">${item.year} · ${typeName(item.type)}${item.duration ? ` · ${Math.round(item.duration / 60)} 分钟` : collection ? ` · ${item.members.length} 部` : ''}</p><div class="detail-actions">${primary}${again}</div></div></div>${item.type === 'movie' && saved ? `<div class="detail-progress">${progress(id)}<p>已观看 ${time(saved)} / ${time(item.duration)}</p></div>` : ''}<div class="detail-body"><p class="synopsis">${item.description}</p>${inner}</div></div>`;
  }
  function settings() {
    const playback = state.tab === 'playback';
    return `<div class="page-pad settings-page"><div class="settings-tabs"><button class="${!playback ? 'active' : ''}" data-settings-tab="subscription" aria-pressed="${!playback}">订阅</button><button class="${playback ? 'active' : ''}" data-settings-tab="playback" aria-pressed="${playback}">播放</button></div>${!playback ? `<section class="settings-section"><h2>当前订阅</h2><div class="subscription-state"><i class="state-dot"></i><strong>家庭影音</strong><small>已同步 · 刚刚</small></div><div class="subscription-url"><span>https://hub.example.net/config#••••••••</span><button class="icon-button" data-action="copy" aria-label="复制订阅链接">${icon('copy')}</button></div><div class="subscription-actions"><button class="play-primary" data-action="sync">${icon('refresh')}立即同步</button><button class="button-secondary" data-action="edit-subscription">更换订阅</button></div></section><section class="settings-section"><h2>连接信息</h2><div class="key-value"><span>连接方式</span><strong>HTTP 代理</strong></div><div class="key-value"><span>代理地址</span><strong>proxy.example.net:8080</strong></div><div class="key-value"><span>Jellyfin 服务</span><strong>https://media.example.net/jellyfin/</strong></div><div class="key-value"><span>账号</span><strong>家庭账号</strong></div></section><p class="settings-note">连接与画质由订阅统一管理。更换订阅需要确认密码。</p>` : `<section class="settings-section"><h2>播放画质</h2><div class="key-value"><span>分辨率上限</span><strong>1080p</strong></div><div class="key-value"><span>合计码率上限</span><strong>4 Mbps</strong></div><p class="settings-note">由订阅管理，音视频合计不超过此上限。</p></section><section class="settings-section"><h2>语言偏好</h2><button class="setting-row" data-language="audio"><strong>首选音频语言</strong><span class="setting-value">${preferences.audio}${icon('chevron')}</span></button><button class="setting-row" data-language="subtitle"><strong>字幕语言偏好</strong><span class="setting-value">${preferences.subtitle}${icon('chevron')}</span></button><button class="setting-row" data-action="default-audio" role="switch" aria-checked="${preferences.defaultAudio}"><span><strong>优先播放默认音轨</strong><small>优先采用片源标记的默认音轨</small></span><span class="toggle ${preferences.defaultAudio ? 'on' : ''}"></span></button></section><p class="settings-note">语言偏好用于后续播放；当前影片仍可在播放器中切换音轨和字幕。</p>`}<div class="app-version">z-jellyfin · 标准版设计预览</div></div>`;
  }
  function search() {
    const query = state.query.trim();
    const results = Object.keys(media).filter(id => media[id].type !== 'episode' && (!query || media[id].name.includes(query)));
    return `<div class="page-pad"><label class="search-input">${icon('search')}<input id="search-query" aria-label="搜索电影、剧集" placeholder="搜索电影、剧集" value="${escape(state.query)}" autocomplete="off"></label><p class="search-meta">${query ? `“${escape(query)}” · ` : ''}${results.length} 个结果</p><div class="poster-grid">${results.map(id => posterCard(id)).join('')}</div>${!results.length ? `<div class="empty">${icon('search')}没有找到相关内容<br>换个名称再试试</div>` : ''}</div>`;
  }
  function queue() { return state.collection ? media[state.collection].members : media[playerId]?.parent ? episodesOf(media[playerId].parent) : []; }
  function player() {
    playerId = media[state.item]?.duration ? state.item : 'ep1';
    const item = media[playerId], parent = media[item.parent || playerId];
    position = state.start ?? resume(playerId); paused = false; preparing = true;
    const list = queue(), index = list.indexOf(playerId), unit = state.collection ? '部' : '集';
    return `<section class="player" aria-label="全屏播放器"><div class="scene">${art(parent.art)}</div><div class="player-top"><button class="icon-button" data-action="back" aria-label="退出播放">${icon('back')}</button><div class="player-titles"><h2>${parent.name}</h2><p>${item.type === 'episode' ? `${episodeLabel(item)} · ${item.name}` : state.collection ? `${media[state.collection].name} · 第 ${index+1} 部` : `${item.year} · 电影`}</p></div><div class="player-tools"><button data-track="subtitle" aria-label="切换字幕">${icon('subtitle')}<span>字幕</span></button><button data-track="audio" aria-label="切换音轨">${icon('audio')}<span>音轨</span></button></div></div><div class="player-center">${list.length ? `<button class="skip" data-step="-1" ${index <= 0 ? 'disabled' : ''} aria-label="上一${unit}">${icon('prev')}<small>上一${unit}</small></button>` : ''}<button class="seek" data-seek="-5" aria-label="后退5秒">${icon('rewind')}<span class="amount">5</span><small>后退 5 秒</small></button><button class="main-toggle" data-action="pause" aria-label="暂停">${icon('pause')}</button><button class="seek" data-seek="15" aria-label="前进15秒">${icon('forward')}<span class="amount">15</span><small>前进 15 秒</small></button>${list.length ? `<button class="skip" data-step="1" ${index >= list.length-1 ? 'disabled' : ''} aria-label="下一${unit}">${icon('next')}<small>下一${unit}</small></button>` : ''}</div><div class="player-bottom"><div class="timeline-meta"><span><span class="elapsed">${time(position)}</span><span class="total"> / ${time(item.duration)}</span></span><span class="quality">1080p · 4 Mbps</span></div><input class="timeline" type="range" min="0" max="${item.duration}" value="${position}" aria-label="播放进度" style="--played:${100*position/item.duration}%"></div><div class="player-caption" ${subtitle === '关闭' ? 'hidden' : ''}>夜色深处，总有人记得来时的路。</div><div class="track-menu" hidden></div><div class="buffer-indicator"><i class="spinner"></i>正在准备播放</div></section>`;
  }
  function showControls() { $('.player')?.classList.remove('controls-hidden'); resetHide(); }
  function resetHide() { clearTimeout(hideTimer); if(playerId && !paused && !preparing && !scrubbing && !menu) hideTimer = setTimeout(() => $('.player')?.classList.add('controls-hidden'),5000); }
  function updateTimeline() { const range = $('.timeline'); if(!range) return; range.value = position; range.style.setProperty('--played',`${100*position/media[playerId].duration}%`); $('.elapsed').textContent = time(position); }
  function openTracks(kind) {
    menu = menu === kind ? '' : kind; showControls();
    const box = $('.track-menu'); box.hidden = !menu;
    if(!menu) return;
    box.dataset.kind = kind;
    const choices = kind === 'subtitle' ? ['简体中文 · SRT','繁体中文 · SRT','关闭'] : ['国语 · AAC 2.0','粤语 · AAC 2.0'];
    const chosen = kind === 'subtitle' ? subtitle : audio;
    box.innerHTML = choices.map(option => `<button class="${option === chosen ? 'active' : ''} ${option === '关闭' ? 'off' : ''}" data-track-value="${option}" role="menuitemradio" aria-checked="${option === chosen}"><span>${option}</span>${option === chosen ? icon('check') : ''}</button>`).join('');
    box.setAttribute('role','menu');
  }
  function startPlayer() {
    const id = playerId;
    setTimeout(() => { if(playerId !== id || state.page !== 'player') return; preparing = false; $('.buffer-indicator').hidden = true; resetHide(); },550);
    ticker = setInterval(() => {
      if(paused || preparing || scrubbing) return;
      position = Math.min(media[playerId].duration,position+1); updateTimeline();
      if(position >= media[playerId].duration) { records[playerId] = position; const list = queue(), next = list[list.indexOf(playerId)+1]; if(next) switchPlay(next); else back(); }
    },1000);
  }
  function switchPlay(id) { savePosition(); menu = ''; state.item = id; state.start = resume(id); history.replaceState(state,'',urlFor(state)); render(); }
  function render() {
    const page = state.page;
    if(!['home','library','detail','collection','search','settings','player'].includes(page)) state.page = 'home';
    document.body.dataset.player = String(state.page === 'player'); phone.dataset.page = state.page;
    const homePage = state.page === 'home';
    topbar.innerHTML = homePage ? `<a class="brand" href="?screen=home"><img src="../logo.png" alt="">z-jellyfin</a>` : `<button class="icon-button back" data-action="back" aria-label="返回">${icon('back')}</button><span class="top-title">${{library:'媒体库',detail:'媒体详情',collection:'电影系列',search:'搜索',settings:'设置'}[state.page] || ''}</span>`;
    topbar.innerHTML += `<div class="top-tools">${state.page !== 'search' ? `<button class="icon-button" data-page="search" aria-label="搜索">${icon('search')}</button>` : ''}${state.page !== 'settings' ? `<button class="icon-button" data-page="settings" aria-label="设置">${icon('settings')}</button>` : ''}</div>`;
    const activeTab = ['library','detail','collection'].includes(state.page) ? 'library' : state.page === 'settings' ? 'settings' : 'home';
    tabbar.innerHTML = [['home','首页'],['library','媒体库'],['settings','设置']].map(([id,label]) => `<button data-page="${id}" class="${activeTab === id ? 'active' : ''}" aria-label="${label}" ${activeTab === id ? 'aria-current="page"' : ''}>${icon(id)}<span>${label}</span></button>`).join('');
    content.innerHTML = ({home,library,detail,collection:() => detail(true),settings,search,player}[state.page])();
    content.scrollTop = state.scroll || 0;
    const reviewKey = state.page === 'home' && state.feed === 'latest' ? 'latest' : state.page === 'detail' ? media[state.item]?.type === 'movie' ? 'movie' : 'series' : state.page;
    document.querySelectorAll('[data-review]').forEach(link => link.classList.toggle('active',link.dataset.review === reviewKey));
    if(state.page === 'player') startPlayer();
  }
  function languageDialog(kind) {
    const selected = preferences[kind];
    sheet.innerHTML = `<h2>${kind === 'audio' ? '首选音频语言' : '字幕语言偏好'}</h2><div class="option-list">${['Chinese','English','Japanese','不指定'].map(label => `<button class="option ${selected === label ? 'active' : ''}" data-choice="${label}" data-kind="${kind}">${label}${selected === label ? icon('check') : ''}</button>`).join('')}</div>`;
    openSheet();
  }
  document.addEventListener('click',async event => {
    const button = event.target.closest('button');
    if(!button || button.disabled) { if(state.page === 'player' && !event.target.closest('.player-top,.player-bottom,.player-center,.track-menu')) { if(menu) { menu=''; $('.track-menu').hidden=true; showControls(); } else $('.player').classList.contains('controls-hidden') ? showControls() : $('.player').classList.add('controls-hidden'); } return; }
    if(button.dataset.page) { markInside(); go({page:button.dataset.page,library:state.library,feed:state.feed}); }
    else if(button.dataset.details) details(button.dataset.details);
    else if(button.dataset.play) play(button.dataset.play,button.dataset.start === undefined ? undefined : Number(button.dataset.start),button.dataset.queue || '');
    else if(button.dataset.library) { state.library=button.dataset.library; state.scroll=0; history.replaceState(state,'',urlFor(state)); render(); }
    else if(button.dataset.season) { state.season=Number(button.dataset.season); state.scroll=content.scrollTop; history.replaceState(state,'',urlFor(state)); render(); }
    else if(button.dataset.settingsTab) { state.tab=button.dataset.settingsTab; state.scroll=0; history.replaceState(state,'',urlFor(state)); render(); }
    else if(button.dataset.language) languageDialog(button.dataset.language);
    else if(button.dataset.choice) { preferences[button.dataset.kind]=button.dataset.choice; sheet.close(); render(); toast('语言偏好已保存（演示）'); }
    else if(button.dataset.track) openTracks(button.dataset.track);
    else if(button.dataset.trackValue) { if(menu === 'subtitle') { subtitle=button.dataset.trackValue; $('.player-caption').hidden=subtitle === '关闭'; } else audio=button.dataset.trackValue; menu=''; $('.track-menu').hidden=true; showControls(); }
    else if(button.dataset.seek) { position=Math.max(0,Math.min(media[playerId].duration,position+Number(button.dataset.seek))); updateTimeline(); showControls(); }
    else if(button.dataset.step) { const list=queue(), next=list[list.indexOf(playerId)+Number(button.dataset.step)]; if(next) switchPlay(next); }
    else if(button.dataset.action === 'back') back();
    else if(button.dataset.action === 'pause') { paused=!paused; button.innerHTML=icon(paused ? 'play' : 'pause'); button.setAttribute('aria-label',paused ? '播放' : '暂停'); showControls(); }
    else if(button.dataset.action === 'default-audio') { preferences.defaultAudio=!preferences.defaultAudio; render(); }
    else if(button.dataset.action === 'sync') toast('订阅已同步（演示）');
    else if(button.dataset.action === 'copy') { try { await navigator.clipboard.writeText('https://hub.example.net/config#DEMO_SUBSCRIPTION_TOKEN'); toast('示例订阅链接已复制'); } catch { toast('请手动复制示例链接'); } }
    else if(button.dataset.action === 'edit-subscription') { sheet.innerHTML='<h2>更换订阅</h2><form id="subscription-form"><label>订阅链接<input name="link" type="url" required placeholder="https://hub.example.net/config#TOKEN" autocomplete="off"></label><p class="helper">仅演示页面操作，不连接实际服务器。</p><div class="dialog-actions"><button class="button-secondary" type="button" data-action="close-dialog">取消</button><button class="play-primary" type="submit">保存并连接</button></div></form>'; openSheet(); }
    else if(button.dataset.action === 'close-dialog') sheet.close();
  });
  let searchTimer;
  document.addEventListener('input',event => {
    if(event.target.matches('.timeline')) { position=Number(event.target.value); scrubbing=true; updateTimeline(); showControls(); }
    if(event.target.id === 'search-query') { clearTimeout(searchTimer); const query=event.target.value; searchTimer=setTimeout(() => { if(state.page !== 'search') return; state.query=query; const cursor=event.target.selectionStart; render(); $('#search-query').focus(); $('#search-query').setSelectionRange(cursor,cursor); },160); }
  });
  document.addEventListener('change',event => { if(event.target.matches('.timeline')) { scrubbing=false; showControls(); } });
  document.addEventListener('submit',event => {
    if(event.target.id === 'subscription-form') { event.preventDefault(); sheet.innerHTML='<h2>确认更换订阅</h2><form id="confirm-form"><label>确认密码<input name="password" type="password" required autocomplete="off" placeholder="请输入确认密码"></label><p class="error" role="alert"></p><div class="dialog-actions"><button class="button-secondary" type="button" data-action="close-dialog">取消</button><button class="play-primary" type="submit">确认保存</button></div></form>'; sheet.querySelector('input').focus(); }
    else if(event.target.id === 'confirm-form') { event.preventDefault(); if(event.target.elements.password.value !== 'jellyfin') sheet.querySelector('.error').textContent='确认密码不正确'; else { sheet.close(); toast('订阅设置已更新（演示）'); } }
  });
  sheet.addEventListener('click',event => { if(event.target !== sheet) return; const box=sheet.getBoundingClientRect(); if(event.clientX < box.left || event.clientX > box.right || event.clientY < box.top || event.clientY > box.bottom) sheet.close(); });
  document.addEventListener('keydown',event => { if(event.key === 'Escape' && menu) { menu=''; $('.track-menu').hidden=true; showControls(); } });
  const gesture = {moved:false,ended:0};
  content.addEventListener('pointerdown',event => { gesture.x=event.clientX; gesture.y=event.clientY; gesture.scroll=content.scrollTop; gesture.moved=false; if(event.target.matches('.timeline')) { scrubbing=true; showControls(); } },true);
  content.addEventListener('pointermove',event => { if(Math.hypot(event.clientX-gesture.x,event.clientY-gesture.y)>7 || Math.abs(content.scrollTop-gesture.scroll)>3) gesture.moved=true; },true);
  content.addEventListener('pointercancel',() => { gesture.moved=true; gesture.ended=Date.now(); scrubbing=false; resetHide(); },true);
  content.addEventListener('pointerup',() => { gesture.ended=Date.now(); if(scrubbing) { scrubbing=false; resetHide(); } },true);
  content.addEventListener('click',event => { if(event.detail !== 0 && gesture.moved && Date.now()-gesture.ended<450) { event.preventDefault(); event.stopImmediatePropagation(); } },true);
  window.addEventListener('popstate',event => { savePosition(); playerId=''; menu=''; state=event.state || initial(); render(); });
  window.addEventListener('pagehide',savePosition);
  window.addEventListener('resize',() => { if(sheet.open) placeSheet(); });
  history.replaceState(state,'',location.href);
  render();
})();
