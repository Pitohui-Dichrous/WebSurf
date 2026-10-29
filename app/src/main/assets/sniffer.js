(function () {
  'use strict';
  if (window.__websurf) return;
  const documentId = Date.now().toString(36) + Math.random().toString(36).slice(2);
  const media = new Map(), byElement = new WeakMap(), sent = new Map(), queued = [];
  const frameSessions = new Map(), gestureClaims = new WeakMap();
  const proxyKeys = {currentTime:'time', duration:'duration', paused:'paused', ended:'ended', playbackRate:'rate', volume:'volume', muted:'muted',readyState:'readyState',error:'error'};
  let epoch = null, nextId = 1, scanPending = false, layoutPending = false, disposed = false, layoutRevision = 0;
  let explicitDark = null;
  const colorPreference = window.matchMedia ? window.matchMedia('(prefers-color-scheme: dark)') : null;
  let gesture = {element:null, at:-Infinity};
  const number = (value, fallback = 0) => Number.isFinite(value) ? value : fallback;
  function direct(data) { try { window.WebSurfObserver.postMessage(JSON.stringify(data)); } catch (_) {} }
  function route(data) {
    if (window.parent !== window && data.mediaId) {
      try { window.parent.postMessage({__websurfEvent:true, event:data}, '*'); } catch (_) {}
    } else direct(data);
  }
  function emit(data) {
    data.page = location.href; data.documentId = documentId;
    if (epoch === null && data.type !== 'hello') { if (queued.length < 400) queued.push(data); return; }
    data.epoch = epoch; route(data);
  }
  try { window.WebSurfObserver.onmessage = event => {
    let ack; try { ack = JSON.parse(event.data); } catch (_) { return; }
    if (ack.documentId !== documentId || !Number.isFinite(ack.epoch)) return;
    const changed=epoch!==null&&epoch!==ack.epoch;
    epoch = ack.epoch; if(typeof ack.dark==='boolean')setTheme(ack.dark); queued.splice(0).forEach(emit);
    // A same-URL navigation can advance native's epoch after document-start injection already
    // received its first ack. Paused media has no periodic play event to repair that lost snapshot.
    if(changed){sent.clear();media.forEach(state=>{state.metadataSignature='';state.layoutSignature='';});scan();
      postChildren({__websurfHandshake:true});}
  }; } catch (_) {}
  function hello() { if (!disposed) emit({type:'hello'}); }
  hello(); setTimeout(hello, 500);

  function claimPlayGesture(element) {
    const event=window.event;
    if(!event||!event.isTrusted||!['click','pointerup','pointerdown','touchend','keydown'].includes(event.type))return;
    if(event.type==='keydown'&&![' ','Enter','MediaPlayPause'].includes(event.key))return;
    const claimed=gestureClaims.get(event);
    if(claimed&&claimed!==element)return;
    gestureClaims.set(event,element);gesture={element,at:Date.now()};
  }
  // The exact receiver of play() during a trusted handler identifies custom controls even
  // when their buttons live outside the video container. No global userActivation window.
  try {
    const prototype=window.HTMLMediaElement&&window.HTMLMediaElement.prototype;
    if(prototype){
      const descriptor=Object.getOwnPropertyDescriptor(prototype,'play'),original=prototype.play;
      Object.defineProperty(prototype,'play',Object.assign({},descriptor,{configurable:true,writable:true,value:function(){
        claimPlayGesture(this);return original.apply(this,arguments);
      }}));
    }
  }catch(_){}

  function rect(left, top, width, height) {
    width = Math.max(0, number(width)); height = Math.max(0, number(height));
    return {left:number(left), top:number(top), right:number(left)+width, bottom:number(top)+height, width, height};
  }
  function intersect(a, b) {
    return rect(Math.max(a.left,b.left), Math.max(a.top,b.top),
      Math.max(0,Math.min(a.right,b.right)-Math.max(a.left,b.left)), Math.max(0,Math.min(a.bottom,b.bottom)-Math.max(a.top,b.top)));
  }
  function viewport() {
    const vv = window.visualViewport;
    return {width:number(vv && vv.width,window.innerWidth || document.documentElement.clientWidth),
      height:number(vv && vv.height,window.innerHeight || document.documentElement.clientHeight),
      x:number(vv && vv.offsetLeft), y:number(vv && vv.offsetTop)};
  }
  function elementRect(element, vp) {
    const r = element.getBoundingClientRect(); return rect(r.left-vp.x,r.top-vp.y,r.width,r.height);
  }
  function clipped(element, bounds, vp, includeViewport = true) {
    let clip = includeViewport ? intersect(bounds,rect(0,0,vp.width,vp.height)) : Object.assign({},bounds);
    for (let node=element; node && node!==document; node=node.parentElement) {
      let css; try { css=window.getComputedStyle(node); } catch (_) { continue; }
      if (css.display==='none' || css.visibility==='hidden' || css.visibility==='collapse') return rect(clip.left,clip.top,0,0);
      if (node===element) continue;
      const ox=/hidden|clip|scroll|auto/.test(css.overflowX || css.overflow || '');
      const oy=/hidden|clip|scroll|auto/.test(css.overflowY || css.overflow || '');
      if (!ox && !oy) continue;
      const r=elementRect(node,vp), sx=node.offsetWidth ? r.width/node.offsetWidth : 1, sy=node.offsetHeight ? r.height/node.offsetHeight : 1;
      const content=rect(r.left+number(node.clientLeft)*sx,r.top+number(node.clientTop)*sy,
        number(node.clientWidth,r.width/sx)*sx,number(node.clientHeight,r.height/sy)*sy);
      clip=intersect(clip,rect(ox?content.left:clip.left,oy?content.top:clip.top,ox?content.width:clip.width,oy?content.height:clip.height));
    }
    return clip;
  }
  function scrollPolicy(element) {
    for(let node=element;node&&node!==document;node=node.parentElement){
      let css;try{css=window.getComputedStyle(node);}catch(_){continue;}
      if(css.position==='fixed'||css.position==='sticky')return 'viewport';
    }
    return 'document';
  }
  function viewportSnapshot(vp) {
    return {viewportWidth:vp.width,viewportHeight:vp.height,
      topScrollX:number(window.scrollX),topScrollY:number(window.scrollY),
      visualOffsetX:vp.x,visualOffsetY:vp.y,
      viewportScale:number(window.visualViewport&&window.visualViewport.scale,1),layoutRevision:++layoutRevision};
  }
  function geometry(element) {
    const vp=viewport(),r=elementRect(element,vp);
    return Object.assign({rect:r,clip:clipped(element,r,vp),contentClip:clipped(element,r,vp,false),
      scrollPolicy:scrollPolicy(element)},viewportSnapshot(vp));
  }
  function geometrySignature(g) {
    return JSON.stringify([g.rect,g.clip,g.contentClip,g.viewportWidth,g.viewportHeight,g.topScrollX,g.topScrollY,
      g.visualOffsetX,g.visualOffsetY,g.viewportScale,g.scrollPolicy]);
  }
  function frames() { return Array.from(document.querySelectorAll('iframe')); }
  function frameSource(frame){return (frame.getAttribute('src')||'')+'|'+(frame.getAttribute('srcdoc')||'');}
  function invalidateFrame(session,reason){
    session.targets.forEach(event=>{
      session.blockedDocuments.add(event.documentId);
      route(Object.assign({},event,{type:'removed',reason}));
    });session.targets.clear();if(session.inputs)session.inputs.clear();
  }
  function checkFrames(){
    const current=new Set(frames());
    frameSessions.forEach((session,frame)=>{
      if(!current.has(frame)||frame.isConnected===false){invalidateFrame(session,'frame-removed');frameSessions.delete(frame);}
      else if(session.source!==frameSource(frame)){invalidateFrame(session,'frame-navigated');session.source=frameSource(frame);}
    });
  }
  function postChildren(data) { frames().forEach(f=>{try {f.contentWindow.postMessage(data,'*');} catch (_) {}}); }
  function frameGeometry(frame, event) {
    if (!event.rect || !event.clip || !(event.viewportWidth>0) || !(event.viewportHeight>0)) return null;
    const vp=viewport(), b=elementRect(frame,vp);
    const bx=frame.offsetWidth?b.width/frame.offsetWidth:1, by=frame.offsetHeight?b.height/frame.offsetHeight:1;
    const content=rect(b.left+number(frame.clientLeft)*bx,b.top+number(frame.clientTop)*by,
      number(frame.clientWidth,b.width/bx)*bx,number(frame.clientHeight,b.height/by)*by);
    const sx=content.width/event.viewportWidth, sy=content.height/event.viewportHeight;
    const transform=r=>rect(content.left+r.left*sx,content.top+r.top*sy,r.width*sx,r.height*sy);
    // The frame viewport clips its descendants even when the outer document has not clipped yet.
    const innerClip=intersect(transform(event.contentClip||event.clip),content);
    return Object.assign({},event,{rect:transform(event.rect),
      clip:intersect(transform(event.clip),clipped(frame,content,vp)),
      contentClip:intersect(innerClip,clipped(frame,content,vp,false)),
      scrollPolicy:scrollPolicy(frame)},viewportSnapshot(vp));
  }
  function resource(value,mime,source,extra) {
    if (!value) return;
    let url; try {url=new URL(value,location.href).href;} catch (_) {return;}
    if (!/^https?:/i.test(url)) return;
    const key=url+'|'+(mime||'')+'|'+source, signature=extra?JSON.stringify(extra):'';
    if (sent.has(key)&&sent.get(key)===signature) return;
    if (sent.size>=500) sent.delete(sent.keys().next().value); sent.set(key,signature);
    emit(Object.assign({type:'resource',url,mime:mime||'',source},extra||{}));
  }
  function sourceOf(v) {return v.getAttribute('src')?v.src||'':v.currentSrc||'';}
  function httpUrl(value) {
    if(typeof value!=='string'||!value.trim()||value.length>16384)return '';
    try {const url=new URL(value,location.href);return /^https?:$/.test(url.protocol)&&!url.username&&!url.password?url.href:'';}catch(_){return '';}
  }
  function qualitySources(state) {
    const sources=Array.from(state.element.querySelectorAll('source'));
    // Ad SDKs often keep the movie's <source> children while temporarily assigning an ad src.
    // A source outside this exact element's declared alternatives cannot inherit their qualities.
    if(!sources.some(source=>httpUrl(source.src)===state.url))return [];
    const choices=[],seen=new Set();let budget=8192;
    for(const source of sources){
      const url=httpUrl(source.src);if(!url||seen.has(url))continue;
      const raw=['label','data-quality','data-res','res','size','data-resolution'].map(key=>source.getAttribute(key)||'').find(value=>value.trim());
      if(!raw)continue;
      const label=String(raw).trim().slice(0,64),dimensions=label.match(/^(\d{2,5})\s*[x×]\s*(\d{2,5})$/i),edge=label.match(/^(\d{2,5})\s*p?$/i);
      const width=dimensions?Number(dimensions[1]):0,height=dimensions?Number(dimensions[2]):edge?Number(edge[1]):0;
      if(width>16384||height>16384)continue;
      const choice={url,mime:(source.type||'').slice(0,128),label:edge?height+'p':label,width,height},length=JSON.stringify(choice).length;
      if(length>budget)continue;
      choices.push(choice);seen.add(url);budget-=length;
      if(choices.length>=12)break;
    }
    return choices;
  }
  function posterOf(v) {
    try{const value=v.poster||v.getAttribute('poster');if(!value)return '';const url=new URL(value,location.href);
      return /^https?:$/.test(url.protocol)?url.href:'';}catch(_){return '';}
  }
  function declaredSource(v) {return (v.getAttribute('src')||'')+'|'+Array.from(v.querySelectorAll('source')).map(s=>s.src+':'+s.type).join('|');}
  function logical(v) {return {time:number(v.currentTime),duration:number(v.duration),paused:!!v.paused,ended:!!v.ended,
    rate:number(v.playbackRate,1),volume:number(v.volume,1),muted:!!v.muted,readyState:number(v.readyState),error:v.error?{code:v.error.code,message:v.error.message||''}:null};}
  function sessionState(state) {
    const values=logical(state.element);
    if(!state.attached&&state.pendingWebSeek&&state.pendingWebSeek.version===state.version)values.time=state.pendingWebSeek.time;
    // currentSrc and decoder metadata can still describe the old pre-roll after src is assigned.
    if(state.awaitingSource&&!state.attached){values.time=0;values.duration=0;values.ended=false;values.readyState=0;values.error=null;
      if(state.qualityTransfer&&state.qualityTransfer.url===state.url)Object.assign(values,state.qualityTransfer.values,{readyState:0,error:null,ended:false});}
    return values;
  }
  function payload(state,type,extra) {
    const v=state.element;
    return Object.assign({type,mediaId:state.id,sourceVersion:state.version,url:state.url,width:state.awaitingSource?0:v.videoWidth||0,height:state.awaitingSource?0:v.videoHeight||0,
      title:document.title||'',poster:posterOf(v),qualitySources:qualitySources(state),nativeFailed:!!state.nativeFailed,video:v.tagName==='VIDEO',hasAudio:typeof v.mozHasAudio==='boolean'?v.mozHasAudio:
        (v.audioTracks?v.audioTracks.length>0:(v.webkitAudioDecodedByteCount>0?true:null)),userInitiated:false,attached:state.attached},
      sessionState(state),geometry(v),extra||{});
  }
  function report(state,type,extra) {emit(payload(state,type,extra));}
  function isUserPlay(state) {return !state.nativeFailed&&gesture.element===state.element&&Date.now()-gesture.at<1800;}
  function request(state,action,extra) {report(state,'request',Object.assign({action},extra||{}));}
  function dispatch(state,name) {
    const previous=state.syncEvent,event=new Event(name);state.syncEvent=event;
    try{state.element.dispatchEvent(event);}finally{state.syncEvent=previous;}
  }
  function restoreProxy(state) {
    state.saved.forEach((saved,name)=>{try {if(saved) Object.defineProperty(state.element,name,saved); else delete state.element[name];} catch (_) {}});
    state.saved.clear();
  }
  function installProxy(state) {
    const v=state.element;
    try {
      Object.keys(proxyKeys).concat(['play','pause']).forEach(name=>state.saved.set(name,Object.getOwnPropertyDescriptor(v,name)));
      Object.keys(proxyKeys).forEach(name=>{
        const d={configurable:true,enumerable:true,get:()=>state.logical[proxyKeys[name]]};
        if (['currentTime','playbackRate','volume','muted'].includes(name)) d.set=value=>{
          inspect(v);
          if(!state.attached){v[name]=value;return;}
          if (name!=='muted'&&!Number.isFinite(Number(value))) return;
          const key=proxyKeys[name]; value=name==='muted'?!!value:Number(value);
          if (name==='currentTime') value=Math.max(0,value);
          if (name==='volume') value=Math.min(1,Math.max(0,value));
          if (name==='playbackRate'&&value<=0) return;
          state.logical[key]=value;
          request(state,name==='currentTime'?'seek':name==='playbackRate'?'rate':'volume',{[key]:value});
        };
        Object.defineProperty(v,name,d);
      });
      Object.defineProperty(v,'play',{configurable:true,value:function(){
        claimPlayGesture(v);
        inspect(v);if(!state.attached)return state.originalPlay.call(v);
        request(state,'play',{userInitiated:isUserPlay(state)});return Promise.resolve();}});
      Object.defineProperty(v,'pause',{configurable:true,value:function(){
        inspect(v);if(!state.attached)return state.originalPause.call(v);request(state,'pause');}});
      return true;
    } catch (_) {restoreProxy(state);return false;}
  }
  function removeButton(state) {
    if(state.buttonRail)state.buttonRail.remove();else if(state.button)state.button.remove();
    state.button=null;state.buttonRail=null;state.buttonAnchor=null;state.buttonTheme=null;
  }
  function buttonAnchor(element) {
    let anchor=element;
    for(let depth=0;depth<8&&anchor.parentElement&&anchor.parentElement!==document.body;depth++){
      const parent=anchor.parentElement;let own,outer;
      try{own=window.getComputedStyle(anchor);outer=window.getComputedStyle(parent);}catch(_){break;}
      const boundary=parent.getBoundingClientRect(),video=element.getBoundingClientRect();
      const positioned=own.position==='absolute'||own.position==='fixed';
      const specialLayout=/flex|grid/.test(outer.display||'');
      const clippedBelow=/hidden|clip|scroll|auto/.test(outer.overflowY||outer.overflow||'')&&boundary.bottom<video.bottom+48;
      if(!positioned&&!specialLayout&&!clippedBelow)break;
      anchor=parent;
    }
    return anchor;
  }
  function setStyle(element,values) {
    Object.keys(values).forEach(key=>{
      const value=String(values[key]);
      if(element.style.getPropertyValue(key)!==value)element.style.setProperty(key,value,'important');
    });
  }
  function applyButtonTheme(state) {
    if(!state.button)return;
    const dark=explicitDark===null?!!(colorPreference&&colorPreference.matches):explicitDark;
    if(state.buttonTheme===dark)return;
    state.buttonTheme=dark;
    setStyle(state.button,{background:dark?'#c0fe03':'#000000',color:dark?'#000000':'#c0fe03',
      'border-color':dark?'#c0fe03':'#000000','color-scheme':dark?'dark':'light'});
    state.button.setAttribute('data-websurf-theme',dark?'dark':'light');
  }
  function updateButton(state) {
    if(!state.button||!state.buttonRail)return;
    const anchor=buttonAnchor(state.element),parent=anchor.parentElement;
    if(!parent)return;
    if(state.buttonAnchor!==anchor||state.buttonRail.parentElement!==parent){
      parent.insertBefore(state.buttonRail,anchor.nextSibling);state.buttonAnchor=anchor;
    }
    const bounds=state.element.getBoundingClientRect();
    let visible=true;
    for(let node=state.element;node&&node!==document;node=node.parentElement){
      let css;try{css=window.getComputedStyle(node);}catch(_){continue;}
      if(css.display==='none'||css.visibility==='hidden'||css.visibility==='collapse'){visible=false;break;}
    }
    // A normal-flow sibling reserves a separate row. Scrolling moves it with the page,
    // without a fixed overlay covering the video's subtitles, badges or quality control.
    setStyle(state.buttonRail,{display:visible&&bounds.width>0&&bounds.height>0?'flex':'none'});
    const railBounds=state.buttonRail.getBoundingClientRect();
    setStyle(state.button,{'margin-right':Math.max(0,Math.min(Math.max(0,railBounds.width-98),railBounds.right-bounds.right))+'px'});
    applyButtonTheme(state);
  }
  function showButton(state) {
    if(state.element.tagName!=='VIDEO'||state.button||!document.body)return;
    const rail=document.createElement('div');rail.setAttribute('data-websurf-ui','takeover-rail');
    rail.style.cssText='all:initial!important;box-sizing:border-box!important;display:flex!important;position:relative!important;clear:both!important;width:100%!important;min-width:0!important;max-width:100%!important;height:48px!important;min-height:48px!important;flex-shrink:0!important;grid-column:1 / -1!important;align-items:center!important;justify-content:flex-end!important;padding:2px 0!important;';
    const button=document.createElement('button');button.type='button';button.textContent='接管播放';button.setAttribute('aria-label','使用 WebSurf 播放此视频');button.setAttribute('data-websurf-control','');
    button.style.cssText='all:initial!important;box-sizing:border-box!important;display:flex!important;position:relative!important;align-items:center!important;justify-content:center!important;min-width:98px!important;height:44px!important;min-height:44px!important;padding:0 14px!important;border:1px solid!important;border-radius:0!important;font:700 14px system-ui,sans-serif!important;letter-spacing:.04em!important;cursor:pointer!important;touch-action:manipulation!important;';
    button.addEventListener('click',e=>{e.preventDefault();e.stopPropagation();if(e.isTrusted!==true||!state.element.isConnected)return;
      inspect(state.element);report(state,'takeover',{userInitiated:true});});
    rail.appendChild(button);state.button=button;state.buttonRail=rail;updateButton(state);
  }
  function setTheme(dark) {
    explicitDark=dark;media.forEach(applyButtonTheme);
    postChildren({__websurfCommand:true,command:{action:'theme',dark}});
  }
  if(colorPreference){
    const changed=()=>{if(explicitDark===null)media.forEach(applyButtonTheme);};
    if(colorPreference.addEventListener)colorPreference.addEventListener('change',changed);
    else if(colorPreference.addListener)colorPreference.addListener(changed);
  }
  function release(state,options) {
    const attached=state.attached,last=state.logical;state.attached=false;restoreProxy(state);
    if(options.failed)state.nativeFailed=true;
    if(state.style){if(state.style.value)state.element.style.setProperty('opacity',state.style.value,state.style.priority);
      else state.element.style.removeProperty('opacity');state.style=null;}
    if(attached&&!options.sourceChanged&&state.element.isConnected){
      state.suppressPlayUntil=Date.now()+1000;
      const time=Number.isFinite(options.time)?Math.max(0,options.time):last.time;
      state.pendingWebSeek={version:state.version,time};
      try{state.element.currentTime=time;if(state.element.readyState>=1)state.pendingWebSeek=null;}catch(_){}
      try{state.element.playbackRate=last.rate;state.element.volume=last.volume;state.element.muted=last.muted;}catch(_){}
      if(options.resume===true){try{const result=state.originalPlay.call(state.element);if(result&&result.catch)result.catch(()=>{});}catch(_){}}
    }
    if(options.button!==false)showButton(state);else removeButton(state);
  }
  function attach(state,value) {
    if(!state.url||!/^https?:/i.test(state.url)){report(state,'error',{reason:'webpage-source-required'});return false;}
    if(state.attached)return true;
    // A quality transfer can preserve playing intent while src selection already paused Chromium.
    // Count only a real decoder pause, otherwise the next webpage pause after fallback is swallowed.
    const decoderWasPlaying=!state.element.paused;
    state.logical=sessionState(state);state.qualityTransfer=null;state.pendingWebSeek=null;state.nativeFailed=false;if(Number.isFinite(value.time))state.logical.time=value.time;
    state.style={value:state.element.style.getPropertyValue('opacity'),priority:state.element.style.getPropertyPriority('opacity')};
    state.attached=true;state.hasNativeSync=false;state.buffering=false;
    state.metadataEmitted=state.logical.readyState>=1;state.dataEmitted=state.logical.readyState>=2;
    if(!installProxy(state)){state.attached=false;state.style=null;report(state,'error',{reason:'bridge-attach-unavailable'});return false;}
    state.element.style.setProperty('opacity','0','important');removeButton(state);
    if(decoderWasPlaying)state.pendingDecoderPause++;
    try{state.originalPause.call(state.element);}catch(_){}
    report(state,'attached');scheduleLayout();return true;
  }
  function synchronize(state,value) {
    if(!state.attached)return false;
    // A separate native decoder failing is not an HTMLMediaElement failure. Forwarding it as a
    // DOM error sends ad SDKs into their own error screens before the original decoder can resume.
    if(value.error)return false;
    const previous=Object.assign({},state.logical),version=state.version,firstSync=!state.hasNativeSync,wasBuffering=state.buffering;
    state.hasNativeSync=true;
    ['time','duration','rate','volume'].forEach(key=>{if(Number.isFinite(value[key]))state.logical[key]=value[key];});
    ['paused','ended','muted'].forEach(key=>{if(typeof value[key]==='boolean')state.logical[key]=value[key];});
    if(typeof value.buffering==='boolean')state.buffering=value.buffering;
    if(Number.isFinite(value.readyState))state.logical.readyState=Math.max(0,Math.min(4,Math.floor(value.readyState)));
    else if(value.ready===true)state.logical.readyState=4;
    else if(value.buffering===true)state.logical.readyState=Math.min(state.logical.readyState,2);
    if(Object.prototype.hasOwnProperty.call(value,'error')){
      state.logical.error=value.error?{code:number(value.error.code,4),message:typeof value.error==='string'?value.error:String(value.error.message||'')}:null;
      if(state.logical.error){state.logical.paused=true;state.buffering=false;}
    }
    if(state.logical.ended)state.logical.paused=true;
    const changed=name=>state.logical[name]!==previous[name],events=[];
    if(state.logical.readyState>=1&&!state.metadataEmitted){state.metadataEmitted=true;events.push('loadedmetadata');}
    if(state.logical.readyState>=2&&!state.dataEmitted){state.dataEmitted=true;events.push('loadeddata');}
    if(state.logical.readyState>=3&&(previous.readyState<3||wasBuffering)&&!state.buffering)events.push('canplay');
    if(changed('duration'))events.push('durationchange');if(changed('rate'))events.push('ratechange');
    if(changed('volume')||changed('muted'))events.push('volumechange');
    if(changed('paused'))events.push(state.logical.paused?'pause':'play');
    if(state.buffering&&!wasBuffering)events.push('waiting');
    if((changed('paused')||firstSync||wasBuffering||previous.readyState<3)&&state.logical.readyState>=3&&!state.logical.paused&&!state.buffering&&!state.logical.error)events.push('playing');
    if(changed('time')||value.ended===true)events.push('timeupdate');if(changed('ended')&&state.logical.ended)events.push('ended');
    if(state.logical.error&&JSON.stringify(state.logical.error)!==JSON.stringify(previous.error))events.push('error');
    for(const event of events){if(!state.attached||state.version!==version)break;dispatch(state,event);}
    // The site's ended handler may synchronously assign the main source after its pre-roll.
    inspect(state.element);return true;
  }
  function sourceChanged(state,url,declared) {
    const previousUrl=state.url,wasAttached=state.attached,previousNativeFailed=!!state.nativeFailed;
    const qualitySwitch=!!state.qualityTransfer&&state.qualityTransfer.url===url;
    release(state,{sourceChanged:true,button:false});
    state.pendingWebSeek=null;if(!qualitySwitch)state.qualityTransfer=null;
    state.nativeFailed=false;state.version++;state.url=url;state.declared=declared;state.awaitingSource=true;
    report(state,'source',{previousUrl,wasAttached,previousNativeFailed,qualitySwitch});
  }
  function restoreQualityTransfer(state) {
    const transfer=state.qualityTransfer;if(!transfer||state.attached||transfer.url!==state.url)return;
    const v=state.element,values=transfer.values;state.qualityTransfer=null;state.suppressPlayUntil=Date.now()+1000;
    try{const duration=number(v.duration);v.currentTime=duration>0?Math.min(values.time,Math.max(0,duration-.01)):values.time;
      v.playbackRate=values.rate;v.volume=values.volume;v.muted=values.muted;}catch(_){}
    if(!values.paused){try{const played=state.originalPlay.call(v);if(played&&played.catch)played.catch(()=>{});}catch(_){}}
  }
  function restoreWebSeek(state) {
    const pending=state.pendingWebSeek;
    if(!pending||state.attached||pending.version!==state.version||state.element.readyState<1)return;
    state.pendingWebSeek=null;
    try{const duration=number(state.element.duration);state.element.currentTime=duration>0?Math.min(pending.time,Math.max(0,duration-.01)):pending.time;}catch(_){}
  }
  function selectQuality(state,value) {
    const choice=qualitySources(state).find(source=>source.url===value.url);if(!choice)return false;
    if(choice.url===state.url)return true;
    const values=sessionState(state);
    if(Number.isFinite(value.time))values.time=Math.max(0,value.time);
    if(typeof value.playIntent==='boolean')values.paused=!value.playIntent;
    else if(typeof value.paused==='boolean')values.paused=value.paused;
    state.qualityTransfer={url:choice.url,values};
    // Setting src invokes the browser's ordinary media loading lifecycle. The page itself stays
    // intact; neither load() nor navigation is used, and the new source event carries its position.
    state.element.src=choice.url;inspect(state.element);return true;
  }
  function inspect(v,reason) {
    let state=byElement.get(v),added=false;
    if(!state){
      added=true;state={id:String(nextId++),element:v,version:1,url:sourceOf(v),declared:declaredSource(v),actual:v.currentSrc||'',
        attached:false,saved:new Map(),syncEvent:null,originalPlay:v.play,originalPause:v.pause,logical:logical(v),style:null,button:null,
        suppressPlayUntil:0,pendingDecoderPause:0,awaitingSource:false,nativeFailed:false,qualityTransfer:null,pendingWebSeek:null,listeners:[]};byElement.set(v,state);media.set(state.id,state);
      const listen=(name,callback)=>{v.addEventListener(name,callback);state.listeners.push([name,callback]);};
      ['loadedmetadata','durationchange','resize','loadstart'].forEach(name=>listen(name,e=>{if(state.syncEvent!==e){inspect(v,name);if(name==='loadedmetadata')restoreWebSeek(state);}}));
      listen('emptied',e=>{if(state.syncEvent===e)return;
        const declared=declaredSource(v),url=v.src||'';
        // src assignment may have already announced this transition before the queued emptied.
        if(!state.awaitingSource||declared!==state.declared||(url&&url!==state.url))sourceChanged(state,url,declared);
        state.actual='';});
      listen('play',e=>{if(state.syncEvent===e||state.attached)return;inspect(v);report(state,'play',
        {userInitiated:Date.now()>=state.suppressPlayUntil&&isUserPlay(state)});});
      ['pause','ended'].forEach(name=>listen(name,e=>{if(state.syncEvent!==e&&!state.attached)report(state,'stop',{reason:name});}));
      // Paused videos do not need a polling loop, but their controls can still change.
      // Keep the native candidate fresh without converting these changes into play intent.
      ['volumechange','ratechange','seeked'].forEach(name=>listen(name,e=>{
        if(state.syncEvent===e||state.attached)return;
        inspect(v);report(state,'media');
      }));
    }else{
      const declared=declaredSource(v),actual=v.currentSrc||'',declarationChanged=declared!==state.declared;
      const actualChanged=actual&&actual!==state.actual&&actual!==state.url;
      if(declarationChanged||(actualChanged&&(!state.awaitingSource||reason==='loadedmetadata')))sourceChanged(state,declarationChanged?(v.src||sourceOf(v)):actual,declared);
      state.actual=actual;
      if(state.awaitingSource&&actual===state.url&&(reason==='loadedmetadata'||(!state.attached&&v.readyState>=1))){state.awaitingSource=false;restoreQualityTransfer(state);}
    }
    const poster=posterOf(v);
    resource(state.url,'','网页媒体',{width:state.awaitingSource?0:v.videoWidth||0,height:state.awaitingSource?0:v.videoHeight||0,poster});
    v.querySelectorAll('source').forEach(s=>resource(s.src,s.type,'媒体源',{poster}));
    const signature=JSON.stringify([state.url,state.awaitingSource,v.videoWidth||0,v.videoHeight||0,sessionState(state).duration,posterOf(v),qualitySources(state)]);
    if(signature!==state.metadataSignature){state.metadataSignature=signature;report(state,'media');}
    if(added&&!v.paused&&!v.ended)report(state,'play');return state;
  }
  function forget(state) {
    report(state,'removed');release(state,{sourceChanged:true,button:false});
    state.listeners.forEach(([name,callback])=>state.element.removeEventListener(name,callback));media.delete(state.id);byElement.delete(state.element);
  }
  function scan() {
    scanPending=false;if(disposed)return;checkFrames();media.forEach(state=>{if(!state.element.isConnected)forget(state);});
    document.querySelectorAll('video,audio').forEach(inspect);document.querySelectorAll('img').forEach(i=>resource(i.currentSrc||i.src,'image/*','网页图片'));
    document.querySelectorAll('a[download]').forEach(a=>resource(a.href,a.type,'下载链接'));scheduleLayout();
  }
  function scheduleScan(){if(!scanPending&&!disposed){scanPending=true;setTimeout(scan,100);}}
  function layouts(){
    layoutPending=false;if(disposed)return;
    media.forEach(state=>{if(!state.element.isConnected)return;const g=geometry(state.element),signature=geometrySignature(g);
      if(state.layoutSignature!==signature){state.layoutSignature=signature;report(state,'layout');}
      updateButton(state);});
    checkFrames();frameSessions.forEach((session,frame)=>{
      if(!session.inputs)return;
      session.inputs.forEach((event,key)=>{
        const transformed=frameGeometry(frame,event);if(!transformed)return;
        const previous=session.targets.get(key);
        if(!previous||geometrySignature(previous)!==geometrySignature(transformed)){
          transformed.type='layout';session.targets.set(key,transformed);route(transformed);
        }
      });
    });postChildren({__websurfLayout:true});
  }
  function scheduleLayout(){if(!layoutPending&&!disposed){layoutPending=true;if(window.requestAnimationFrame)window.requestAnimationFrame(layouts);else setTimeout(layouts,16);}}
  function motionTick(){
    if(disposed)return;
    media.forEach(state=>{if(state.attached||state.button||!state.element.paused){report(state,'layout');updateButton(state);}});
    setTimeout(motionTick,200);
  }
  function command(value){
    if(!value||typeof value!=='object')return false;
    if(value.action==='theme'&&typeof value.dark==='boolean'){setTheme(value.dark);return true;}
    if(value.action==='probe'&&!value.documentId){media.forEach(state=>report(state,'media'));layouts();postChildren({__websurfCommand:true,command:value});return true;}
    if(value.documentId!==documentId){postChildren({__websurfCommand:true,command:value});return false;}
    const state=media.get(String(value.mediaId));
    if(!state||!state.element.isConnected)return false;
    inspect(state.element);
    if(value.sourceVersion!=null&&value.sourceVersion!==state.version)return false;
    if(value.action==='attach')return attach(state,value);
    if(value.action==='detach'){
      const failed=value.failed===true||value.reason==='native-failure';
      if(failed&&value.failedSourceVersion!=null&&value.failedSourceVersion!==state.version)return false;
      release(state,{time:value.time,resume:value.resume===true,button:value.button!==false,failed});report(state,'detached');return true;}
    if(value.action==='quality')return selectQuality(state,value);
    if(value.action==='sync')return synchronize(state,value);
    if(value.action==='probe'){inspect(state.element);report(state,'media');return true;}
    if(value.action==='seek'){if(state.attached)return synchronize(state,{time:value.time});if(Number.isFinite(value.time)){try{state.element.currentTime=Math.max(0,value.time);}catch(_){}}return true;}
    if(value.action==='play'||value.action==='pause'){
      if(state.attached)return synchronize(state,{paused:value.action==='pause',ended:false});state.suppressPlayUntil=Date.now()+1000;
      try{const result=(value.action==='play'?state.originalPlay:state.originalPause).call(state.element);if(result&&result.catch)result.catch(()=>{});}catch(_){}return true;
    }return false;
  }
  window.__websurf={scan,command,documentId};
  window.addEventListener('message',e=>{
    const data=e.data;if(!data||typeof data!=='object')return;
    if(e.source===window.parent&&window.parent!==window){if(data.__websurfCommand===true)command(data.command);if(data.__websurfLayout===true)scheduleLayout();if(data.__websurfHandshake===true)hello();return;}
    if(data.__websurfEvent!==true||!data.event||!data.event.mediaId)return;
    checkFrames();const frame=frames().find(f=>f.contentWindow===e.source);if(!frame)return;
    let session=frameSessions.get(frame);
    if(!session){session={source:frameSource(frame),targets:new Map(),inputs:new Map(),blockedDocuments:new Set()};frameSessions.set(frame,session);
      if(explicitDark!==null){try{frame.contentWindow.postMessage({__websurfCommand:true,command:{action:'theme',dark:explicitDark}},'*');}catch(_){}}}
    if(session.blockedDocuments.has(data.event.documentId))return;
    const transformed=frameGeometry(frame,data.event);
    if(transformed){
      const key=transformed.documentId+'|'+transformed.mediaId;
      if(transformed.type==='removed'){session.targets.delete(key);session.inputs.delete(key);}else{session.targets.set(key,transformed);session.inputs.set(key,data.event);}
      route(transformed);
    }
  });
  function noteGesture(e){
    if(!e.isTrusted||(e.type==='keydown'&&![' ','Enter','MediaPlayPause'].includes(e.key)))return;
    const path=e.composedPath?e.composedPath():[e.target];let target=path.find(node=>byElement.has(node));
    if(!target){for(let node=e.target;node&&node!==document.body&&node!==document.documentElement;node=node.parentElement){
      if(!node.querySelectorAll)continue;const candidates=Array.from(node.querySelectorAll('video,audio'));
      if(candidates.length===1){target=candidates[0];break;}if(candidates.length>1)break;
    }}
    if(!target&&Number.isFinite(e.clientX)&&Number.isFinite(e.clientY)){
      const matches=Array.from(media.values()).filter(state=>{const r=state.element.getBoundingClientRect();return e.clientX>=r.left&&e.clientX<=r.right&&e.clientY>=r.top&&e.clientY<=r.bottom;});
      if(matches.length===1)target=matches[0].element;
    }gesture={element:target||null,at:Date.now()};
  }
  ['pointerdown','click','touchstart','keydown'].forEach(name=>document.addEventListener(name,noteGesture,true));
  // Decoder events are replaced only on the attached element. Sync events still reach ad SDKs.
  ['play','playing','pause','ended','timeupdate','durationchange','ratechange','volumechange','loadedmetadata','loadeddata','canplay','canplaythrough','waiting','stalled','error'].forEach(name=>document.addEventListener(name,e=>{
    const state=byElement.get(e.target);if(!state||state.syncEvent===e)return;
    if(name==='pause'&&state.pendingDecoderPause>0){state.pendingDecoderPause--;e.stopImmediatePropagation();return;}
    if(!state.attached)return;
    if(name==='loadedmetadata')inspect(state.element,name);
    if(!state.attached)return;e.stopImmediatePropagation();
    if(name==='play'){request(state,'play',{userInitiated:isUserPlay(state)});state.pendingDecoderPause++;try{state.originalPause.call(state.element);}catch(_){}}
  },true));
  window.addEventListener('scroll',scheduleLayout,true);window.addEventListener('resize',scheduleLayout);
  if(window.visualViewport){window.visualViewport.addEventListener('resize',scheduleLayout);window.visualViewport.addEventListener('scroll',scheduleLayout);}
  window.addEventListener('pagehide',e=>{if(e.persisted){media.forEach(state=>release(state,{button:true}));return;}media.forEach(forget);disposed=true;});
  window.addEventListener('pageshow',e=>{if(e.persisted){epoch=null;queued.length=0;hello();scan();}});
  const originalFetch=window.fetch;
  if(originalFetch)window.fetch=function(){return originalFetch.apply(this,arguments).then(response=>{resource(response.url,response.headers.get('content-type'),'Fetch');return response;});};
  const originalOpen=XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.open=function(){this.addEventListener('load',function(){resource(this.responseURL,this.getResponseHeader('content-type'),'XHR');},{once:true});return originalOpen.apply(this,arguments);};
  try{new PerformanceObserver(list=>list.getEntries().forEach(e=>resource(e.name,'','资源时间线'))).observe({type:'resource',buffered:true});}catch(_){}
  function ready(){scan();new MutationObserver(()=>{scheduleLayout();scheduleScan();}).observe(document.documentElement,{childList:true,subtree:true,attributes:true,attributeFilter:['src','srcdoc','srcset','poster','style','class','hidden','label','data-quality','data-res','res','size','data-resolution']});motionTick();}
  if(document.documentElement)ready();else document.addEventListener('DOMContentLoaded',ready,{once:true});
})();
