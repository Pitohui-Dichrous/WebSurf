#!/usr/bin/env node
// Contract tests run the shipped observer against a small DOM/media model. Media's native
// decoder state is separate from JS properties, matching HTMLMediaElement instance proxies.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';
const source=readFileSync(new URL('../app/src/main/assets/sniffer.js',import.meta.url),'utf8');

class FakeEvent {
  constructor(type, options={}) {Object.assign(this,{type,isTrusted:false,stopped:false,immediate:false},options);}
  stopImmediatePropagation(){this.immediate=true;this.stopped=true;}
  stopPropagation(){this.stopped=true;}
  preventDefault(){this.defaultPrevented=true;}
  composedPath(){const path=[];for(let n=this.target;n;n=n.parentElement)path.push(n);return path;}
}
class Events {
  constructor(){this.listeners=new Map();}
  addEventListener(type,callback,options={}){
    if(!this.listeners.has(type))this.listeners.set(type,[]);
    this.listeners.get(type).push({callback,once:options.once===true,capture:options===true||options.capture===true});
  }
  removeEventListener(type,callback){this.listeners.set(type,(this.listeners.get(type)||[]).filter(e=>e.callback!==callback));}
  invoke(event,capture){
    for(const entry of [...(this.listeners.get(event.type)||[])]){
      if(entry.capture!==capture)continue;
      if(entry.once)this.removeEventListener(event.type,entry.callback);
      entry.callback.call(this,event);if(event.immediate)break;
    }
  }
  dispatchEvent(event){
    event.target ||= this;
    const window=this.ownerDocument?.defaultView||this.defaultView,previous=window?.event;
    if(window)window.event=event;
    try{
      if(this.ownerDocument&&this.ownerDocument!==this)this.ownerDocument.invoke(event,true);
      if(!event.immediate)this.invoke(event,true);
      if(!event.immediate)this.invoke(event,false);
    }finally{if(window)window.event=previous;}
    return !event.defaultPrevented;
  }
  dispatch(type,options={}){return this.dispatchEvent(new FakeEvent(type,{isTrusted:true,...options}));}
}
class Style {
  constructor(){this.values=new Map();}
  getPropertyValue(key){return this.values.get(key)?.value||'';}
  getPropertyPriority(key){return this.values.get(key)?.priority||'';}
  setProperty(key,value,priority=''){this.values.set(key,{value,priority});}
  removeProperty(key){this.values.delete(key);}
}
function bounds(left=10,top=30,width=320,height=180){return {left,top,width,height,right:left+width,bottom:top+height};}
class Element extends Events {
  constructor(tag='DIV',box=bounds()){
    super();Object.assign(this,{tagName:tag,box,style:new Style(),attributes:{},children:[],parentElement:null,isConnected:true,css:{},
      offsetWidth:box.width,offsetHeight:box.height,clientWidth:box.width,clientHeight:box.height,clientLeft:0,clientTop:0});
  }
  getBoundingClientRect(){return this.box;}
  getAttribute(key){return this.attributes[key]??null;}
  setAttribute(key,value){this.attributes[key]=String(value);}
  appendChild(child){return this.insertBefore(child,null);}
  insertBefore(child,before){
    if(child.parentElement)child.parentElement.children=child.parentElement.children.filter(e=>e!==child);
    child.parentElement=this;child.ownerDocument=this.ownerDocument;child.isConnected=this.isConnected;
    const index=before?this.children.indexOf(before):-1;
    if(index<0)this.children.push(child);else this.children.splice(index,0,child);return child;
  }
  get nextSibling(){const siblings=this.parentElement?.children||[];return siblings[siblings.indexOf(this)+1]||null;}
  remove(){this.isConnected=false;for(const child of this.querySelectorAll('*'))child.isConnected=false;
    if(this.parentElement)this.parentElement.children=this.parentElement.children.filter(e=>e!==this);this.parentElement=null;}

  querySelectorAll(selector){
    const all=this.children.flatMap(child=>[child,...child.querySelectorAll('*')]);
    if(selector==='*')return all;
    if(selector==='video,audio')return all.filter(e=>['VIDEO','AUDIO'].includes(e.tagName));
    if(selector==='source')return all.filter(e=>e.tagName==='SOURCE');
    if(selector==='button')return all.filter(e=>e.tagName==='BUTTON');
    return [];
  }
}
class Media extends Element {
  constructor({url='https://media.example/video.mp4',playing=false,width=640,height=360,audio=false,box}={}){
    super(audio?'AUDIO':'VIDEO',box||bounds());
    this._native={currentTime:12,duration:120,paused:!playing,ended:false,playbackRate:1,volume:1,muted:false,readyState:4,error:null};
    Object.assign(this,{_src:url,currentSrc:url,videoWidth:width,videoHeight:height,pauseCalls:0,playCalls:0});
    this.attributes.src=url;
  }
  get src(){return this._src;}
  set src(url){this._src=url;this.attributes.src=url;}
  pause(){this.pauseCalls++;if(!this._native.paused){this._native.paused=true;this.dispatch('pause');}}
  play(){this.playCalls++;if(this._native.paused){this._native.paused=false;this._native.ended=false;this.dispatch('play');}return Promise.resolve();}
}
for(const key of ['currentTime','duration','paused','ended','playbackRate','volume','muted','readyState','error'])
  Object.defineProperty(Media.prototype,key,{configurable:true,get(){return this._native[key];},set(v){this._native[key]=v;}});

function harness({media=[],width=400,height=700,childFrame=false,dark=false}={}){
  const messages=[],timers=[],mutations=[],frames=[],images=[],links=[],parentPosts=[];let clock=10000,rafCalls=0;
  const document=new Events();
  document.body=new Element('BODY',bounds(0,0,width,height));document.body.ownerDocument=document;
  document.documentElement=new Element('HTML',bounds(0,0,width,height));document.body.parentElement=document.documentElement;
  Object.assign(document,{title:'Playback fixture',createElement:tag=>{const e=new Element(tag.toUpperCase());e.ownerDocument=document;return e;},
    querySelectorAll(selector){return ({'video,audio':media.filter(m=>m.isConnected),img:images,'a[download]':links,iframe:frames})[selector]||[];}});
  media.forEach(v=>{v.ownerDocument=document;if(!v.parentElement)document.body.appendChild(v);});
  const colorPreference=Object.assign(new Events(),{matches:dark});
  const window=new Events();Object.assign(window,{innerWidth:width,innerHeight:height,scrollX:0,scrollY:0,getComputedStyle:e=>e.css||{},
    matchMedia:()=>colorPreference,requestAnimationFrame(callback){rafCalls++;timers.push({at:clock+16,callback});return timers.length;}});
  document.defaultView=window;
  class PageMedia extends Media{}
  Object.defineProperty(PageMedia.prototype,'play',{configurable:true,writable:true,value:Media.prototype.play});
  window.HTMLMediaElement=PageMedia;
  media.forEach(v=>Object.setPrototypeOf(v,PageMedia.prototype));
  window.parent=childFrame?{postMessage(data){parentPosts.push(data);}}:window;
  const bridge={onmessage:null,postMessage(value){messages.push(JSON.parse(value));}};window.WebSurfObserver=bridge;
  class Xhr extends Events{open(){}}
  class Clock extends Date{static now(){return clock;}}
  const context=vm.createContext({window,document,location:{href:'https://page.example/watch'},URL,Event:FakeEvent,Date:Clock,XMLHttpRequest:Xhr,
    MutationObserver:class{constructor(callback){this.callback=callback;mutations.push(this);}observe(){}},
    PerformanceObserver:class{constructor(callback){this.callback=callback;}observe(){}},
    setTimeout(callback,delay){timers.push({at:clock+delay,callback});return timers.length;}});
  vm.runInContext(source,context,{filename:'sniffer.js'});
  const documentId=messages.find(e=>e.type==='hello')?.documentId;assert.ok(documentId);
  return {media,images,links,frames,messages,window,document,context,documentId,parentPosts,colorPreference,rafCalls:()=>rafCalls,
    events:type=>messages.filter(e=>e.type===type),
    ack(epoch=7,id=documentId){bridge.onmessage({data:JSON.stringify({documentId:id,epoch})});},
    mutation(){mutations.forEach(observer=>observer.callback([]));},
    tick(ms){const end=clock+ms;while(true){timers.sort((a,b)=>a.at-b.at);if(!timers.length||timers[0].at>end)break;const next=timers.shift();clock=next.at;next.callback();}clock=end;},
    scan(){media.forEach(v=>{v.ownerDocument=document;if(!v.parentElement)document.body.appendChild(v);});window.__websurf.scan();},
    command(id,action,extra={}){return window.__websurf.command({documentId,mediaId:String(id),action,...extra});},
    gesture(v,options={}){document.dispatch('pointerdown',{target:v,...options});},
    id(v){return messages.find(e=>e.type==='media'&&e.url===v.src)?.mediaId;},
  };
}

// A native player can safely accept only a deliberate media gesture, never the first autoplay.
test('handshake queues data, ignores a foreign ack, and flushes exactly once with document epoch',()=>{
  const h=harness({media:[new Media({playing:true})]});assert.deepEqual(h.messages.map(e=>e.type),['hello']);
  h.ack(90,'foreign');assert.equal(h.events('play').length,0);h.ack(7);
  assert.equal(h.events('play').length,1);assert.ok(h.events('resource').length);
  for(const e of h.messages.filter(e=>e.type!=='hello')){assert.equal(e.epoch,7);assert.equal(e.documentId,h.documentId);}
  const count=h.messages.length;h.ack();assert.equal(h.messages.length,count);h.tick(500);assert.equal(h.events('hello').length,2);
});
test('independent documents cannot unlock one another',()=>{
  const a=harness(),b=harness({media:[new Media({playing:true})]});assert.notEqual(a.documentId,b.documentId);
  b.ack(7,a.documentId);assert.equal(b.events('play').length,0);b.ack(8);assert.equal(b.events('play')[0].epoch,8);
});
test('an autoplay advertisement does not acquire user intent or suppress a later manually played main video',()=>{
  const ad=new Media({playing:true,url:'https://media.example/ad.mp4'}),main=new Media();const h=harness({media:[ad,main]});h.ack();
  assert.equal(h.events('play')[0].userInitiated,false);h.gesture(main);main.play();
  const event=h.events('play').at(-1);assert.equal(event.url,main.src);assert.equal(event.userInitiated,true);
  assert.equal(ad.pauseCalls,0);assert.equal(main.pauseCalls,0);assert.notEqual(h.id(ad),h.id(main));
});
test('synthetic, unrelated and expired gestures cannot authorize takeover',()=>{
  const main=new Media(),ad=new Media({url:'https://media.example/ad.mp4',box:bounds(10,300)});const h=harness({media:[main,ad]});h.ack();
  h.gesture(main,{isTrusted:false});main.play();assert.equal(h.events('play').at(-1).userInitiated,false);main.pause();
  h.gesture(ad);main.play();assert.equal(h.events('play').at(-1).userInitiated,false);main.pause();
  h.gesture(main);h.tick(1801);main.play();assert.equal(h.events('play').at(-1).userInitiated,false);
});
test('custom controls in a single-video container can authorize the matching media',()=>{
  const main=new Media(),container=new Element(),button=new Element('BUTTON');container.appendChild(main);container.appendChild(button);
  const h=harness({media:[main]});h.ack();h.gesture(button);main.play();assert.equal(h.events('play').at(-1).userInitiated,true);
});
test('an external custom button calling play identifies its exact media even with multiple videos under body',()=>{
  const main=new Media(),ad=new Media({url:'https://media.example/ad.mp4',box:bounds(10,300)}),h=harness({media:[main,ad]});h.ack();
  h.document.body.appendChild(main);h.document.body.appendChild(ad);
  const actions=new Element(),button=new Element('BUTTON');h.document.body.appendChild(actions);actions.appendChild(button);
  let result;button.addEventListener('click',()=>{result=main.play();});button.dispatch('click');
  assert.equal(typeof result.then,'function','the native play promise is preserved');
  assert.equal(h.events('play').at(-1).url,main.src);assert.equal(h.events('play').at(-1).userInitiated,true);
  ad.play();assert.equal(h.events('play').at(-1).url,ad.src);assert.equal(h.events('play').at(-1).userInitiated,false,'a subsequent automatic ad cannot borrow the click');
});
test('a second automatic video started in the same click cannot steal the explicit media call',()=>{
  const main=new Media(),ad=new Media({url:'https://media.example/ad.mp4'}),h=harness({media:[main,ad]});h.ack();
  const button=h.document.createElement('button');h.document.body.appendChild(button);button.addEventListener('click',()=>{main.play();ad.play();});
  button.dispatch('click');assert.equal(h.events('play')[0].userInitiated,true);assert.equal(h.events('play')[1].userInitiated,false);
});
test('a synthetic external button click cannot authorize a prototype play call',()=>{
  const main=new Media(),h=harness({media:[main]});h.ack();const button=h.document.createElement('button');h.document.body.appendChild(button);
  button.addEventListener('click',()=>main.play());button.dispatch('click',{isTrusted:false});assert.equal(h.events('play')[0].userInitiated,false);
});
test('attach pauses and hides only its target, preserving layout and the other playing video',()=>{
  const main=new Media({playing:true}),other=new Media({playing:true,url:'https://media.example/other.mp4'});
  main.style.setProperty('opacity','.8','important');const h=harness({media:[main,other]});h.ack();const id=h.id(main);
  h.command(id,'attach');assert.equal(main._native.paused,true);assert.equal(main.paused,false,'logical playback remains owned by native');
  assert.equal(main.style.getPropertyValue('opacity'),'0');assert.equal(main.box.width,320);assert.equal(other.pauseCalls,0);
  assert.equal(h.events('stop').length,0,'physical pause must not cancel native takeover');
  h.command(id,'detach',{time:42,resume:false});assert.equal(main.currentTime,42);assert.equal(main.paused,true);
  assert.equal(main.style.getPropertyValue('opacity'),'.8');assert.equal(main.style.getPropertyPriority('opacity'),'important');
  assert.equal(Object.hasOwn(main,'paused'),false);assert.equal(Object.hasOwn(main,'play'),false);
});
test('a later main-video play remains actionable while a different video is attached',()=>{
  const one=new Media({playing:true,url:'https://media.example/one.mp4'}),two=new Media();const h=harness({media:[one,two]});h.ack();
  h.command(h.id(one),'attach');h.gesture(two);two.play();assert.equal(h.events('play').at(-1).mediaId,h.id(two));
  assert.equal(h.events('play').at(-1).userInitiated,true);assert.equal(two.pauseCalls,0);
});
test('new autoplay media during takeover remains a candidate without hijacking or being globally paused',()=>{
  const main=new Media({playing:true}),h=harness({media:[main]});h.ack();h.command(h.id(main),'attach');
  const ad=new Media({playing:true,url:'https://media.example/ad.mp4'});ad.ownerDocument=h.document;h.media.push(ad);h.mutation();h.tick(100);
  assert.equal(ad.pauseCalls,0);assert.equal(h.events('play').at(-1).url,ad.src);assert.equal(h.events('play').at(-1).userInitiated,false);
});
test('native sync updates original element state and events without requesting another takeover',()=>{
  const v=new Media({playing:true}),h=harness({media:[v]});h.ack();const id=h.id(v);h.command(id,'attach');
  const observed=[];for(const name of ['timeupdate','durationchange','pause','play','playing','ratechange','volumechange'])v.addEventListener(name,()=>observed.push(name));
  const plays=h.events('play').length;
  h.command(id,'sync',{time:45,duration:130,paused:true,rate:1.5,volume:.4,muted:true});
  assert.equal(v.currentTime,45);assert.equal(v.duration,130);assert.equal(v.paused,true);assert.equal(v.playbackRate,1.5);assert.equal(v.volume,.4);assert.equal(v.muted,true);
  assert.deepEqual(observed,['durationchange','ratechange','volumechange','pause','timeupdate']);
  h.command(id,'sync',{paused:false});assert.deepEqual(observed.slice(-2),['play','playing']);
  assert.equal(h.events('play').length,plays);assert.equal(h.events('stop').length,0);assert.equal(h.events('request').length,0);
});
test('the attached page controls request play, pause, seek, rate and volume without running a second decoder',()=>{
  const v=new Media({playing:true}),h=harness({media:[v]});h.ack();h.command(h.id(v),'attach');
  v.play();v.pause();v.currentTime=77;v.playbackRate=2;v.volume=.2;v.muted=true;
  assert.deepEqual(h.events('request').map(e=>e.action),['play','pause','seek','rate','volume','volume']);
  assert.equal(h.events('request')[2].time,77);assert.equal(h.events('request')[3].rate,2);assert.equal(v._native.paused,true);assert.equal(v.playCalls,0);
});
test('native ended advances a pre-roll SDK to the main source on the same media identity',()=>{
  const v=new Media({playing:true,url:'https://media.example/preroll.mp4'}),h=harness({media:[v]});h.ack();const id=h.id(v);h.command(id,'attach');
  let ends=0;v.addEventListener('ended',()=>{ends++;assert.equal(v.ended,true);assert.equal(v.currentTime,120);v.src='https://media.example/main.mp4';});
  h.command(id,'sync',{time:120,duration:120,ended:true,paused:true});
  assert.equal(ends,1);assert.equal(h.events('source').length,1);const event=h.events('source')[0];
  assert.equal(event.mediaId,id);assert.equal(event.url,'https://media.example/main.mp4');assert.equal(event.wasAttached,true);assert.equal(event.sourceVersion,2);
  assert.equal(event.time,0);assert.equal(event.duration,0);assert.equal(event.ended,false);
  assert.equal(h.events('media').at(-1).time,0,'old decoder time must not overwrite the new source snapshot');
  assert.equal(v.style.getPropertyValue('opacity'),'');assert.equal(Object.hasOwn(v,'ended'),false);
  assert.equal(h.command(id,'sync',{sourceVersion:1,time:60}),false,'a late old-player update cannot corrupt main video');
  v.currentSrc=v.src;v.dispatch('loadedmetadata');assert.equal(h.events('source').length,1,'currentSrc catching up does not create a second transition');
});
test('a pre-roll SDK can change src then call play synchronously from its ended handler',()=>{
  const v=new Media({playing:true,url:'https://media.example/preroll.mp4'}),h=harness({media:[v]});h.ack();const id=h.id(v);h.command(id,'attach');
  v.addEventListener('ended',()=>{v.src='https://media.example/main.mp4';v.play();});
  h.command(id,'sync',{time:120,ended:true,paused:true});
  assert.equal(h.events('source').at(-1).wasAttached,true);assert.equal(v.playCalls,1);
  assert.equal(h.events('request').length,0,'main-video play must not restart the old pre-roll decoder');
  assert.equal(h.events('play').at(-1).url,v.src);
});
test('the first native playing update reaches webpage listeners even when play preceded attach',()=>{
  const v=new Media({playing:true}),h=harness({media:[v]});h.ack();const id=h.id(v);h.command(id,'attach');
  let playing=0;v.addEventListener('playing',()=>playing++);h.command(id,'sync',{paused:false,time:12.5});h.command(id,'sync',{paused:false,time:13});
  assert.equal(playing,1);
});
test('native preparation and buffering advance page events, but native decoder failure is not a DOM error',()=>{
  const v=new Media({playing:true});v.readyState=0;const h=harness({media:[v]});h.ack();const id=h.id(v);h.command(id,'attach');
  const events=[];for(const name of ['loadedmetadata','loadeddata','canplay','playing','waiting','error'])v.addEventListener(name,()=>events.push(name));
  h.command(id,'sync',{readyState:1,paused:false,duration:120});assert.equal(v.readyState,1);assert.deepEqual(events,['loadedmetadata']);
  h.command(id,'sync',{readyState:4,buffering:false});h.command(id,'sync',{readyState:4,buffering:false});
  assert.deepEqual(events,['loadedmetadata','loadeddata','canplay','playing']);
  h.command(id,'sync',{buffering:true});h.command(id,'sync',{buffering:true});assert.equal(v.readyState,2);
  assert.deepEqual(events.slice(-1),['waiting']);
  h.command(id,'sync',{ready:true,buffering:false});assert.deepEqual(events.slice(-2),['canplay','playing']);
  h.command(id,'sync',{error:{code:3,message:'Decode failed'}});h.command(id,'sync',{error:{code:3,message:'Decode failed'}});
  assert.equal(events.filter(name=>name==='error').length,0);assert.equal(v.error,null);assert.equal(v.paused,false,'failed native sync cannot change the saved webpage play intent');
  h.command(id,'detach',{resume:false});assert.equal(Object.hasOwn(v,'readyState'),false);assert.equal(Object.hasOwn(v,'error'),false);assert.equal(v.error,null);
});
test('a delayed decoder pause cannot cancel a detached or newly selected source',()=>{
  const v=new Media({playing:true});v.pause=function(){this.pauseCalls++;this._native.paused=true;};
  const h=harness({media:[v]});h.ack();const id=h.id(v);h.command(id,'attach');h.command(id,'detach',{resume:false});
  v.dispatch('pause');assert.equal(h.events('stop').length,0,'the queued pause belongs to native attachment');
  v.play();v.pause();v.dispatch('pause');assert.equal(h.events('stop').at(-1).reason,'pause','later webpage pauses still propagate');
});
test('an element with non-configurable media properties fails attachment without altering the page',()=>{
  const v=new Media({playing:true});Object.defineProperty(v,'currentTime',{configurable:false,get(){return 12;}});
  const h=harness({media:[v]});h.ack();assert.equal(h.command(h.id(v),'attach'),false);
  assert.equal(v.pauseCalls,0);assert.equal(v.style.getPropertyValue('opacity'),'');assert.equal(Object.hasOwn(v,'play'),false);
  assert.equal(h.events('error')[0].reason,'bridge-attach-unavailable');
});
test('duplicate native ended is dispatched only once before a source transition',()=>{
  const v=new Media({playing:true}),h=harness({media:[v]});h.ack();const id=h.id(v);h.command(id,'attach');let ends=0;v.addEventListener('ended',()=>ends++);
  h.command(id,'sync',{ended:true,time:120});h.command(id,'sync',{ended:true,time:120});assert.equal(ends,1);
});
test('a quality source change invalidates the old attachment and restores page controls',()=>{
  const v=new Media({playing:true}),h=harness({media:[v]});h.ack();const id=h.id(v);h.command(id,'attach');
  v.src='https://media.example/720.mp4';h.mutation();h.tick(100);
  assert.equal(h.events('source').at(-1).wasAttached,true);assert.equal(h.command(id,'attach',{sourceVersion:1}),false);
  assert.equal(Object.hasOwn(v,'currentTime'),false);assert.equal(v.style.getPropertyValue('opacity'),'');
});
test('unsupported blob media stays in its original player',()=>{
  const v=new Media({playing:true,url:'blob:https://page.example/123'}),h=harness({media:[v]});h.ack();
  assert.equal(h.command(h.id(v),'attach'),false);assert.equal(v.pauseCalls,0);assert.equal(v.style.getPropertyValue('opacity'),'');
  assert.equal(h.events('error')[0].reason,'webpage-source-required');
});
test('detach resumes only the target at native position and does not immediately auto-reattach',()=>{
  const main=new Media({playing:true}),idle=new Media({url:'https://media.example/idle.mp4'}),h=harness({media:[main,idle]});h.ack();
  h.gesture(main);h.command(h.id(main),'attach');h.command(h.id(main),'sync',{time:65,rate:1.25});h.command(h.id(main),'detach',{resume:true});
  assert.equal(main.currentTime,65);assert.equal(main.playbackRate,1.25);assert.equal(main._native.paused,false);assert.equal(idle.playCalls,0);
  assert.equal(h.events('play').at(-1).userInitiated,false);assert.equal(h.document.body.querySelectorAll('button').length,1);
});
test('closing creates an immediate reopen action in a flow row below its video',()=>{
  const v=new Media({playing:true}),h=harness({media:[v]});h.ack();const id=h.id(v);h.command(id,'attach');h.command(id,'detach',{resume:false});
  const button=h.document.body.querySelectorAll('button')[0];assert.ok(button);assert.equal(button.textContent,'接管播放');
  button.dispatch('click');const event=h.events('takeover')[0];assert.equal(event.mediaId,id);assert.equal(event.userInitiated,true);
  h.command(id,'attach');assert.equal(button.isConnected,false);assert.equal(v._native.paused,true);
});
test('the persistent reopen action receives fresh webpage playback time in layout updates',()=>{
  const v=new Media({playing:true}),h=harness({media:[v]});h.ack();const id=h.id(v);h.command(id,'attach');h.command(id,'detach',{time:35,resume:true});
  v.currentTime=67;h.tick(200);const event=h.events('layout').at(-1);
  assert.equal(event.time,67);assert.equal(event.paused,false);assert.equal(event.url,v.src);assert.equal(event.mediaId,id);
});
test('paused webpage controls publish fresh mute, volume, rate and seek snapshots without requesting takeover',()=>{
  const v=new Media(),h=harness({media:[v]});h.ack();const id=h.id(v);
  v.muted=true;v.volume=.7;v.dispatch('volumechange');
  let event=h.events('media').at(-1);assert.equal(event.muted,true);assert.equal(event.volume,.7);assert.equal(event.paused,true);
  v.playbackRate=1.75;v.dispatch('ratechange');event=h.events('media').at(-1);assert.equal(event.rate,1.75);assert.equal(event.muted,true);
  v.currentTime=84;v.dispatch('seeked');event=h.events('media').at(-1);
  assert.equal(event.time,84);assert.equal(event.mediaId,id);assert.equal(event.rate,1.75);assert.equal(event.volume,.7);
  assert.equal(h.events('play').length,0);assert.equal(h.events('takeover').length,0);assert.equal(h.events('request').length,0);
  h.command(id,'attach');assert.equal(v.muted,true);assert.equal(v.volume,.7);assert.equal(v.currentTime,84);
  const count=h.events('media').length;h.command(id,'sync',{time:90,rate:2,volume:.4,muted:false});
  assert.equal(h.events('media').length,count,'native sync events must not loop back as webpage control changes');
});
test('removing an attached video removes its session and restores all descriptors',()=>{
  const v=new Media({playing:true}),h=harness({media:[v]});h.ack();const id=h.id(v);h.command(id,'attach');v.isConnected=false;h.scan();
  assert.equal(h.events('removed')[0].mediaId,id);assert.equal(Object.hasOwn(v,'currentTime'),false);assert.equal(h.command(id,'attach'),false);
});
test('layout clips to viewport and overflow ancestors and responds to scroll',()=>{
  const v=new Media({box:bounds(-10,40,320,180)}),parent=new Element('DIV',bounds(0,50,200,120));parent.css.overflow='hidden';parent.appendChild(v);
  const h=harness({media:[v]});h.ack();h.tick(16);const first=h.events('layout').at(-1);
  assert.deepEqual(first.clip,{left:0,top:50,right:200,bottom:170,width:200,height:120});
  v.box=bounds(10,-80,320,180);h.window.dispatch('scroll');h.tick(16);const next=h.events('layout').at(-1);
  assert.equal(next.rect.top,-80);assert.equal(next.clip.height,50);assert.equal(next.viewportWidth,400);
});
test('visual viewport panning and zoom produce coordinates relative to the visible viewport',()=>{
  const v=new Media(),h=harness({media:[v]});h.ack();h.window.visualViewport=Object.assign(new Events(),{width:200,height:350,offsetLeft:20,offsetTop:40});
  h.window.dispatch('resize');h.tick(16);const event=h.events('layout').at(-1);
  assert.equal(event.rect.left,-10);assert.equal(event.rect.top,-10);assert.equal(event.clip.left,0);assert.equal(event.viewportWidth,200);
});
test('a real child iframe is translated, scaled, bordered and clipped before reaching native',()=>{
  const h=harness();h.ack();const child={},frame=new Element('IFRAME',bounds(40,100,220,140));
  Object.assign(frame,{contentWindow:child,offsetWidth:110,offsetHeight:70,clientLeft:5,clientTop:5,clientWidth:100,clientHeight:60});h.frames.push(frame);
  const event={type:'layout',mediaId:'9',documentId:'child-doc',epoch:7,sourceVersion:1,page:'https://child.example/',
    rect:bounds(10,10,100,60),clip:bounds(10,10,90,50),viewportWidth:100,viewportHeight:60};
  h.window.dispatch('message',{source:child,data:{__websurfEvent:true,event}});const output=h.events('layout')[0];
  assert.deepEqual(output.rect,bounds(70,130,200,120));assert.deepEqual(output.clip,bounds(70,130,180,100));
  assert.equal(output.documentId,'child-doc');assert.equal(output.viewportWidth,400);
  h.window.dispatch('message',{source:{},data:{__websurfEvent:true,event}});assert.equal(h.events('layout').length,1);
});
test('nested iframe forwarding preserves child identity and applies each parent transformation',()=>{
  const child=harness({childFrame:true,width:200,height:300}),grandWindow={},frame=new Element('IFRAME',bounds(20,30,100,100));frame.contentWindow=grandWindow;child.frames.push(frame);child.ack();
  const input={type:'play',mediaId:'2',documentId:'grand',epoch:7,rect:bounds(10,5,80,50),clip:bounds(10,5,80,50),viewportWidth:100,viewportHeight:100};
  child.window.dispatch('message',{source:grandWindow,data:{__websurfEvent:true,event:input}});
  const forwarded=child.parentPosts.find(e=>e.__websurfEvent).event;assert.equal(forwarded.rect.left,30);assert.equal(forwarded.rect.top,35);
  const top=harness(),parentWindow={},outer=new Element('IFRAME',bounds(50,80,100,150));outer.contentWindow=parentWindow;top.frames.push(outer);top.ack();
  top.window.dispatch('message',{source:parentWindow,data:{__websurfEvent:true,event:forwarded}});
  const output=top.events('play')[0];assert.equal(output.rect.left,65);assert.equal(output.rect.top,97.5);assert.equal(output.documentId,'grand');
});
test('removing an iframe immediately retires its cached child and nested media sessions',()=>{
  const h=harness(),child={},frame=new Element('IFRAME');frame.contentWindow=child;h.frames.push(frame);h.ack();
  const base={type:'play',mediaId:'1',epoch:7,rect:bounds(0,0,100,60),clip:bounds(0,0,100,60),viewportWidth:320,viewportHeight:180};
  for(const documentId of ['child-doc','nested-doc'])h.window.dispatch('message',{source:child,data:{__websurfEvent:true,event:{...base,documentId}}});
  h.frames.length=0;h.mutation();h.tick(100);
  assert.deepEqual(h.events('removed').map(e=>e.documentId),['child-doc','nested-doc']);
  assert.ok(h.events('removed').every(e=>e.reason==='frame-removed'));
  h.window.dispatch('message',{source:child,data:{__websurfEvent:true,event:{...base,documentId:'child-doc',type:'removed'}}});
  assert.equal(h.events('removed').length,2,'late unload messages cannot revive or double-retire an absent frame');
});
test('iframe src replacement retires the old document and rejects its late observations',()=>{
  const h=harness(),child={},frame=new Element('IFRAME');frame.contentWindow=child;frame.setAttribute('src','https://child.example/old');h.frames.push(frame);h.ack();
  const event={type:'play',mediaId:'1',documentId:'old-doc',epoch:7,rect:bounds(0,0,100,60),clip:bounds(0,0,100,60),viewportWidth:320,viewportHeight:180};
  h.window.dispatch('message',{source:child,data:{__websurfEvent:true,event}});frame.setAttribute('src','https://child.example/new');h.mutation();h.tick(100);
  assert.equal(h.events('removed')[0].documentId,'old-doc');assert.equal(h.events('removed')[0].reason,'frame-navigated');
  h.window.dispatch('message',{source:child,data:{__websurfEvent:true,event}});assert.equal(h.events('play').length,1);
  h.window.dispatch('message',{source:child,data:{__websurfEvent:true,event:{...event,documentId:'new-doc'}}});assert.equal(h.events('play').length,2);
});
test('frame commands accept only their direct parent and address one document and media id',()=>{
  const v=new Media({playing:true}),h=harness({media:[v],childFrame:true});h.ack();const id=h.parentPosts.find(e=>e.event?.type==='media').event.mediaId;
  const data={__websurfCommand:true,command:{documentId:h.documentId,mediaId:id,action:'attach'}};
  h.window.dispatch('message',{source:{},data});assert.equal(v.pauseCalls,0);
  h.window.dispatch('message',{source:h.window.parent,data});assert.equal(v.pauseCalls,1);
  const posted=[],frame=new Element('IFRAME');frame.contentWindow={postMessage:data=>posted.push(data)};h.frames.push(frame);
  h.window.__websurf.command({documentId:'grandchild',mediaId:'4',action:'detach',resume:false});
  assert.equal(posted[0].command.resume,false);assert.equal(posted[0].command.documentId,'grandchild');
});
test('resource observations keep images, downloads, source variants and changed dimensions',()=>{
  const v=new Media({width:640,height:360}),s=new Element('SOURCE');s.src='https://media.example/high.m3u8';s.type='application/vnd.apple.mpegurl';v.appendChild(s);
  const h=harness({media:[v]});h.ack();h.images.push({src:'https://media.example/photo.jpg'});h.links.push({href:'https://media.example/document.pdf',type:'application/pdf'});h.scan();
  v.videoWidth=1280;v.videoHeight=720;v.dispatch('resize');v.videoWidth=640;v.videoHeight=360;v.dispatch('resize');
  const resources=h.events('resource');assert.deepEqual(resources.filter(e=>e.url===v.src).map(e=>e.height),[360,720,360]);
  assert.ok(resources.some(e=>e.url===s.src));assert.ok(resources.some(e=>e.mime==='image/*'));assert.ok(resources.some(e=>e.mime==='application/pdf'));
});
test('reinjection does not duplicate listeners and repeated scans do not repeat play requests',()=>{
  const v=new Media({playing:true}),h=harness({media:[v]});h.ack();vm.runInContext(source,h.context);h.scan();h.scan();
  assert.equal(h.events('hello').length,1);assert.equal(h.events('play').length,1);
});


test('scroll geometry is frame-scheduled, coalesced, and carries a stable document anchor',()=>{
  const v=new Media({box:bounds(10,330,320,180)}),h=harness({media:[v]});h.ack();h.tick(16);
  const first=h.events('layout').at(-1),calls=h.rafCalls();
  h.window.scrollY=260;v.box=bounds(10,70,320,180);
  h.window.dispatch('scroll');h.window.dispatch('scroll');
  assert.equal(h.rafCalls(),calls+1,'one animation-frame callback batches the scroll events');
  h.tick(15);assert.equal(h.events('layout').at(-1),first);
  h.tick(1);const moved=h.events('layout').at(-1);
  assert.equal(moved.topScrollY,260);assert.equal(moved.rect.top,70);
  assert.equal(moved.rect.top+moved.topScrollY+moved.visualOffsetY,first.rect.top);
  assert.ok(moved.layoutRevision>first.layoutRevision);assert.equal(moved.scrollPolicy,'document');
});
test('contentClip preserves the document video when the viewport has scrolled it fully away',()=>{
  const v=new Media({box:bounds(10,-240,320,180)}),h=harness({media:[v]});h.window.scrollY=500;h.ack();h.tick(16);
  const snapshot=h.events('layout').at(-1);
  assert.equal(snapshot.clip.height,0);assert.deepEqual(snapshot.contentClip,bounds(10,-240,320,180));
  assert.equal(snapshot.contentClip.top+snapshot.topScrollY,260);
  const parent=new Element('DIV',bounds(0,-220,220,120));parent.css.overflow='hidden';parent.appendChild(v);
  h.window.dispatch('scroll');h.tick(16);const clipped=h.events('layout').at(-1);
  assert.deepEqual(clipped.contentClip,bounds(10,-220,210,120));assert.equal(clipped.clip.height,0);
});
test('fixed or sticky ancestor geometry explicitly declines document scroll compensation',()=>{
  const v=new Media(),container=new Element();container.css.position='sticky';container.appendChild(v);
  const h=harness({media:[v]});h.ack();h.tick(16);assert.equal(h.events('layout').at(-1).scrollPolicy,'viewport');
  container.css.position='relative';h.mutation();h.tick(16);assert.equal(h.events('layout').at(-1).scrollPolicy,'document');
  v.css.position='fixed';h.mutation();h.tick(16);assert.equal(h.events('layout').at(-1).scrollPolicy,'viewport');
});
test('visual viewport offsets and scale are explicit rather than folded into top-level scroll',()=>{
  const v=new Media(),h=harness({media:[v]});h.ack();h.window.scrollX=120;h.window.scrollY=240;
  h.window.visualViewport=Object.assign(new Events(),{width:200,height:350,offsetLeft:20,offsetTop:40,scale:2});
  h.window.dispatch('resize');h.tick(16);const event=h.events('layout').at(-1);
  assert.equal(event.topScrollX,120);assert.equal(event.topScrollY,240);
  assert.equal(event.visualOffsetX,20);assert.equal(event.visualOffsetY,40);assert.equal(event.viewportScale,2);
  assert.equal(event.rect.left+event.visualOffsetX+event.topScrollX,130);
  assert.equal(event.rect.top+event.visualOffsetY+event.topScrollY,270);
});
test('parent scroll reprojects cached iframe media in the next frame without waiting on a child reply',()=>{
  const h=harness(),child={postMessage(){}},frame=new Element('IFRAME',bounds(20,300,320,180));frame.contentWindow=child;h.frames.push(frame);h.ack();h.tick(16);
  const input={type:'media',mediaId:'1',documentId:'child',epoch:7,sourceVersion:3,url:'https://child.example/main.mp4',
    rect:bounds(0,0,320,180),clip:bounds(0,0,320,180),contentClip:bounds(0,0,320,180),viewportWidth:320,viewportHeight:180,
    topScrollY:900,scrollPolicy:'viewport'};
  h.window.dispatch('message',{source:child,data:{__websurfEvent:true,event:input}});const before=h.events('media').at(-1);
  h.window.scrollY=400;frame.box=bounds(20,-100,320,180);h.window.dispatch('scroll');h.tick(16);
  const moved=h.events('layout').at(-1);assert.equal(moved.documentId,'child');assert.equal(moved.sourceVersion,3);
  assert.equal(moved.rect.top,-100);assert.equal(moved.topScrollY,400);assert.equal(moved.scrollPolicy,'document');
  assert.equal(moved.clip.height,80);assert.equal(moved.contentClip.height,180);
  assert.equal(moved.rect.top+moved.topScrollY,before.rect.top);assert.ok(moved.layoutRevision>before.layoutRevision);
});
test('the reopen action reserves a separate row after the video without changing its parent',()=>{
  const v=new Media({playing:true}),h=harness({media:[v]});h.ack();const next=new Element('P');h.document.body.appendChild(next);
  h.command(h.id(v),'attach');h.command(h.id(v),'detach',{resume:false});
  const button=h.document.body.querySelectorAll('button')[0],rail=button.parentElement;
  assert.equal(v.parentElement,h.document.body);assert.equal(rail.parentElement,h.document.body);
  assert.equal(v.nextSibling,rail);assert.equal(rail.nextSibling,next);
  assert.match(rail.style.cssText,/height:48px!important/);assert.match(button.style.cssText,/height:44px!important/);
  assert.doesNotMatch(rail.style.cssText,/position:(fixed|absolute)/);assert.doesNotMatch(button.style.cssText,/position:(fixed|absolute)/);
  h.command(h.id(v),'attach');assert.equal(rail.isConnected,false);assert.equal(button.isConnected,false);assert.equal(v.nextSibling,next);
});
test('an absolutely positioned video gets its action after the player wrapper instead of inside its clipped viewport',()=>{
  const v=new Media({playing:true}),wrapper=new Element('DIV',bounds(10,30,320,180));
  v.css.position='absolute';wrapper.css.position='relative';wrapper.css.overflow='hidden';wrapper.appendChild(v);
  const h=harness({media:[v]});h.document.body.appendChild(wrapper);h.ack();
  h.command(h.id(v),'attach');h.command(h.id(v),'detach',{resume:false});
  const button=h.document.body.querySelectorAll('button')[0],rail=button.parentElement;
  assert.equal(v.parentElement,wrapper);assert.equal(wrapper.nextSibling,rail);assert.equal(rail.parentElement,h.document.body);
  assert.equal(wrapper.children.length,1,'no control or wrapper is inserted inside the site player');
  assert.equal(v.css.position,'absolute');assert.equal(wrapper.css.overflow,'hidden');
});
test('webpage action uses system colors by default and follows explicit application light and dark themes',()=>{
  const v=new Media({playing:true}),h=harness({media:[v],dark:true});h.ack();h.command(h.id(v),'attach');h.command(h.id(v),'detach',{resume:false});
  const button=h.document.body.querySelectorAll('button')[0];
  assert.equal(button.getAttribute('data-websurf-theme'),'dark');assert.equal(button.style.getPropertyValue('color'),'#a8d5b8');
  h.window.__websurf.command({action:'theme',dark:false});
  assert.equal(button.getAttribute('data-websurf-theme'),'light');assert.equal(button.style.getPropertyValue('background'),'#edf0eb');
  assert.equal(button.style.getPropertyValue('color'),'#145f39');
  h.colorPreference.matches=true;h.colorPreference.dispatch('change');assert.equal(button.getAttribute('data-websurf-theme'),'light');
  h.window.__websurf.command({action:'theme',dark:true});assert.equal(button.style.getPropertyValue('background'),'#2c2c2c');
  assert.equal(button.style.getPropertyValue('color'),'#a8d5b8');
});
test('application theme broadcasts to present and newly observed frame sessions',()=>{
  const h=harness(),posts=[],child={postMessage:data=>posts.push(data)},frame=new Element('IFRAME');frame.contentWindow=child;h.frames.push(frame);h.ack();
  h.window.__websurf.command({action:'theme',dark:true});assert.equal(posts.at(-1).command.dark,true);
  h.window.dispatch('message',{source:child,data:{__websurfEvent:true,event:{type:'media',mediaId:'1',documentId:'new-child',epoch:7,
    rect:bounds(0,0,100,60),clip:bounds(0,0,100,60),viewportWidth:320,viewportHeight:180}}});
  assert.equal(posts.filter(p=>p.command?.action==='theme').length,2);
  const v=new Media({playing:true}),nested=harness({media:[v],childFrame:true});nested.ack();const id=nested.parentPosts.find(p=>p.event?.type==='media').event.mediaId;
  nested.command(id,'attach');nested.command(id,'detach',{resume:false});
  nested.window.dispatch('message',{source:nested.window.parent,data:{__websurfCommand:true,command:{action:'theme',dark:true}}});
  assert.equal(nested.document.body.querySelectorAll('button')[0].getAttribute('data-websurf-theme'),'dark');
});
test('poster metadata is an explicit webpage cover URL and rejects non-network sources',()=>{
  const v=new Media();v.poster='/covers/scene.jpg';const h=harness({media:[v]});h.ack();
  assert.equal(h.events('media').at(-1).poster,'https://page.example/covers/scene.jpg');
  assert.equal(h.events('resource').find(e=>e.url===v.src).poster,'https://page.example/covers/scene.jpg');
  v.poster='javascript:alert(1)';h.mutation();h.tick(100);assert.equal(h.events('media').at(-1).poster,'');
  v.poster='data:image/png;base64,AAAA';h.scan();assert.equal(h.events('media').at(-1).poster,'');
  v.poster='https://images.example/new.jpg';h.scan();assert.equal(h.events('media').at(-1).poster,v.poster);
});

function declaredVariant(v,url,label,mime='video/mp4') {
  const source=new Element('SOURCE');source.src=url;source.type=mime;if(label!=null)source.setAttribute('label',label);v.appendChild(source);return source;
}
test('quality candidates are scoped to explicit labels on this video, never guessed from another video or URL digits',()=>{
  const v=new Media({url:'https://media.example/360.mp4'}),other=new Media({url:'https://media.example/ad.mp4'});
  declaredVariant(v,v.src,'360p');declaredVariant(v,'https://media.example/720.mp4','720');
  declaredVariant(v,'https://media.example/2160-unlabelled.mp4',null);
  declaredVariant(v,'data:text/plain,quality','1080p');declaredVariant(v,'https://user:example-password@media.example/private.mp4','1080p');
  declaredVariant(other,other.src,'1080p');
  const h=harness({media:[v,other]});h.ack();
  const choices=h.events('media').find(e=>e.url===v.src).qualitySources;
  assert.deepEqual(choices.map(q=>[q.url,q.label,q.height]),[[v.src,'360p',360],['https://media.example/720.mp4','720p',720]]);
  assert.equal(h.command(h.id(v),'quality',{url:other.src,sourceVersion:1}),false);
  assert.equal(v.src,'https://media.example/360.mp4');
});
test('temporary ad src does not inherit the movie quality choices left under the same video element',()=>{
  const v=new Media({url:'https://media.example/ad.mp4'});
  declaredVariant(v,'https://media.example/360.mp4','360p');declaredVariant(v,'https://media.example/720.mp4','720p');
  const h=harness({media:[v]});h.ack();assert.deepEqual(h.events('media')[0].qualitySources,[]);
  assert.equal(h.command(h.id(v),'quality',{url:'https://media.example/720.mp4'}),false);
});
test('quality switch preserves native time and intent through a new source generation without loading the page',()=>{
  const v=new Media({url:'https://media.example/360.mp4',playing:true});
  declaredVariant(v,v.src,'360p');declaredVariant(v,'https://media.example/720.mp4','1280 × 720');
  let loads=0;v.load=()=>loads++;const h=harness({media:[v]});h.ack();const id=h.id(v);h.command(id,'attach');
  h.command(id,'sync',{time:57,rate:1.5,volume:.4,muted:true,paused:false});
  assert.equal(h.command(id,'quality',{url:'https://media.example/720.mp4',sourceVersion:1}),true);
  const changed=h.events('source').at(-1);
  assert.equal(changed.wasAttached,true);assert.equal(changed.qualitySwitch,true);assert.equal(changed.sourceVersion,2);
  assert.equal(changed.time,57);assert.equal(changed.rate,1.5);assert.equal(changed.volume,.4);assert.equal(changed.muted,true);assert.equal(changed.paused,false);
  assert.equal(loads,0);assert.equal(h.command(id,'quality',{url:'https://media.example/360.mp4',sourceVersion:1}),false);
  assert.equal(h.command(id,'attach',{sourceVersion:2}),true);assert.equal(v.currentTime,57);assert.equal(v.playbackRate,1.5);
  v.currentSrc=v.src;v.dispatch('emptied');v.dispatch('loadedmetadata');assert.equal(h.events('source').length,1,'queued emptied and metadata must not restart the new native session');
});
test('quality switch while webpage-owned restores the decoder at metadata and respects paused intent',()=>{
  const v=new Media({url:'https://media.example/360.mp4'});v.currentTime=39;v.playbackRate=1.25;v.volume=.6;
  declaredVariant(v,v.src,'360p');declaredVariant(v,'https://media.example/720.mp4','720p');
  const h=harness({media:[v]});h.ack();const id=h.id(v);
  h.command(id,'quality',{url:'https://media.example/720.mp4',sourceVersion:1});
  v.currentSrc='';v.currentTime=0;v.readyState=0;v.dispatch('emptied');
  v.currentSrc=v.src;v.readyState=4;v.dispatch('loadedmetadata');
  assert.equal(v.currentTime,39);assert.equal(v.playbackRate,1.25);assert.equal(v.volume,.6);assert.equal(v.playCalls,0);assert.equal(v.paused,true);
  assert.equal(h.events('source').length,1);
});
test('failure fallback restores original decoding without a load and blocks only the failed source',()=>{
  const v=new Media({url:'https://media.example/ad.mp4',playing:true});let loads=0;v.load=()=>loads++;
  const h=harness({media:[v]});h.ack();const id=h.id(v);h.gesture(v);h.command(id,'attach');
  h.command(id,'sync',{time:4,rate:1.25,volume:.6});
  h.command(id,'detach',{sourceVersion:1,failedSourceVersion:1,reason:'native-failure',time:4,resume:true});
  assert.equal(loads,0);assert.equal(v.currentTime,4);assert.equal(v.paused,false);assert.equal(v.playbackRate,1.25);
  assert.equal(v.style.getPropertyValue('opacity'),'');assert.equal(Object.hasOwn(v,'play'),false);
  assert.equal(h.events('detached').at(-1).nativeFailed,true);
  h.tick(2000);v.pause();h.gesture(v);v.play();assert.equal(h.events('play').at(-1).userInitiated,false,'same failed source cannot enter an automatic takeover loop');
  v.src='https://media.example/main.mp4';h.scan();const changed=h.events('source').at(-1);
  assert.equal(changed.previousNativeFailed,true);assert.equal(changed.nativeFailed,false);assert.equal(changed.qualitySwitch,false);assert.equal(changed.time,0);
  v.currentSrc=v.src;v.readyState=4;v.dispatch('loadedmetadata');v.pause();h.gesture(v);v.play();assert.equal(h.events('play').at(-1).userInitiated,true);
});
test('failure fallback can be explicitly retried, but a late failure cannot change a replacement source',()=>{
  const v=new Media({playing:true}),h=harness({media:[v]});h.ack();const id=h.id(v);h.command(id,'attach');
  h.command(id,'detach',{reason:'native-failure',failedSourceVersion:1,resume:true});
  const button=h.document.body.querySelectorAll('button')[0];button.dispatch('click');assert.equal(h.events('takeover').at(-1).userInitiated,true);
  h.command(id,'attach',{sourceVersion:1});assert.equal(h.events('attached').at(-1).nativeFailed,false);
  v.src='https://media.example/main.mp4';
  assert.equal(h.command(id,'detach',{reason:'native-failure',sourceVersion:1,failedSourceVersion:1,time:90,resume:true}),false);
  assert.equal(h.events('source').at(-1).url,v.src);assert.equal(h.events('source').at(-1).nativeFailed,false);
  assert.equal(v.playCalls,1,'the stale failure must not start the new decoder');
});
test('initial discovery honors a newly assigned src over stale currentSrc from a prior video',()=>{
  const v=new Media({url:'https://media.example/old-ad.mp4'});v.src='https://media.example/main.mp4';
  const h=harness({media:[v]});h.ack();assert.equal(h.events('media')[0].url,v.src);
});
test('asynchronous emptied after an observed src mutation neither increments twice nor revives old currentSrc',()=>{
  const v=new Media({playing:true}),h=harness({media:[v]});h.ack();const id=h.id(v);h.command(id,'attach');
  v.src='https://media.example/main.mp4';h.scan();assert.equal(h.events('source').length,1);
  v.dispatch('emptied');h.scan();assert.equal(h.events('source').length,1);assert.equal(h.events('media').at(-1).url,v.src);
  v.currentSrc=v.src;v.readyState=4;v.dispatch('loadedmetadata');assert.equal(h.events('source').length,1);
  assert.equal(h.events('media').at(-1).sourceVersion,2);
});
test('a remembered quality choice can carry explicit playback intent before first native attachment',()=>{
  const v=new Media({url:'https://media.example/360.mp4'});declaredVariant(v,v.src,'360p');declaredVariant(v,'https://media.example/720.mp4','720p');
  const h=harness({media:[v]});h.ack();const id=h.id(v);
  assert.equal(h.command(id,'quality',{url:'https://media.example/720.mp4',sourceVersion:1,playIntent:true}),true);
  const event=h.events('source').at(-1);assert.equal(event.wasAttached,false);assert.equal(event.qualitySwitch,true);assert.equal(event.paused,false);
  assert.equal(h.command(id,'attach',{sourceVersion:2}),true);assert.equal(v.paused,false);
});
test('quality metadata has a bounded message budget even when many long URLs are declared',()=>{
  const v=new Media({url:'https://media.example/360.mp4'});declaredVariant(v,v.src,'360p');
  for(let i=0;i<25;i++)declaredVariant(v,'https://media.example/variant'+i+'.mp4?token='+'a'.repeat(2400),'720p');
  const h=harness({media:[v]});h.ack();const event=h.events('media')[0];
  assert.ok(event.qualitySources.length<=12);assert.ok(JSON.stringify(event.qualitySources).length<8250);
  assert.ok(JSON.stringify(event).length<12000);
});
test('fallback defers an unavailable webpage seek until metadata without reloading or losing its checkpoint',()=>{
  const v=new Media({playing:true});v.readyState=0;v._native.currentTime=0;
  Object.defineProperty(v,'currentTime',{configurable:true,get(){return this._native.currentTime;},set(value){if(this._native.readyState<1)throw new Error('metadata not ready');this._native.currentTime=value;}});
  const h=harness({media:[v]});h.ack();const id=h.id(v);h.command(id,'attach');h.command(id,'sync',{time:35,readyState:4});
  h.command(id,'detach',{reason:'native-failure',sourceVersion:1,time:35,resume:true});
  assert.equal(h.events('detached').at(-1).time,35);assert.equal(v.currentTime,0);
  v.readyState=4;v.dispatch('loadedmetadata');assert.equal(v.currentTime,35);assert.equal(v.paused,false);
});
test('queued decoder play and pause during attachment cannot send a native pause request or leak a page pause',()=>{
  const v=new Media({playing:true});v.pause=function(){this.pauseCalls++;this._native.paused=true;};
  const h=harness({media:[v]});h.ack();const id=h.id(v);let pauses=0;v.addEventListener('pause',()=>pauses++);
  h.command(id,'attach');
  // Chromium dispatches media task events asynchronously, possibly after the bridge has attached.
  v.dispatch('play');v.dispatch('playing');v.dispatch('pause');v.dispatch('pause');v.dispatch('pause');
  assert.equal(h.events('request').filter(event=>event.action==='pause').length,0);
  assert.equal(h.events('stop').length,0);assert.equal(pauses,0);assert.equal(v.paused,false);
  h.command(id,'sync',{paused:true});assert.equal(pauses,1,'only a native state change emits this synthetic pause');
  v.pause();assert.equal(h.events('request').at(-1).action,'pause','an explicit website pause still reaches native');
});
test('an updated native epoch republishes a paused video and its qualities without reload or play',()=>{
  const v=new Media({url:'https://media.example/360.mp4'});let loads=0;v.load=()=>loads++;
  declaredVariant(v,v.src,'360p');declaredVariant(v,'https://media.example/720.mp4','720p');
  const h=harness({media:[v]});h.ack(7);h.tick(500);
  const count=h.events('media').length,resources=h.events('resource').length;
  h.ack(8);h.tick(16);
  assert.equal(h.events('media').length,count+1);const latest=h.events('media').at(-1);
  assert.equal(latest.epoch,8);assert.equal(latest.paused,true);assert.equal(latest.sourceVersion,1);
  assert.deepEqual(latest.qualitySources.map(source=>source.height),[360,720]);
  assert.ok(h.events('resource').length>resources);assert.equal(h.events('layout').at(-1).epoch,8);
  assert.equal(h.events('play').length,0);assert.equal(v.playCalls,0);assert.equal(v.pauseCalls,0);assert.equal(loads,0);
  const messages=h.messages.length;h.ack(8);assert.equal(h.messages.length,messages,'duplicate ack does not republish');
});
test('an epoch refresh asks child frames to register their own document without trusting foreign requests',()=>{
  const h=harness(),posted=[],frame=new Element('IFRAME');frame.contentWindow={postMessage:value=>posted.push(value)};h.frames.push(frame);
  h.ack(7);h.ack(8);assert.ok(posted.some(value=>value.__websurfHandshake===true));
  const child=harness({childFrame:true});child.ack(7);const old=child.events('hello').length;
  child.window.dispatch('message',{source:{},data:{__websurfHandshake:true}});assert.equal(child.events('hello').length,old);
  child.window.dispatch('message',{source:child.window.parent,data:{__websurfHandshake:true}});assert.equal(child.events('hello').length,old+1);
});
test('quality transfer with an already paused decoder does not swallow a real webpage pause after failure fallback',()=>{
  const v=new Media({url:'https://media.example/360.mp4',playing:true});
  declaredVariant(v,v.src,'360p');declaredVariant(v,'https://media.example/720.mp4','720p');
  const h=harness({media:[v]});h.ack();const id=h.id(v);let pauses=0;v.addEventListener('pause',()=>pauses++);
  h.command(id,'attach');h.command(id,'quality',{url:'https://media.example/720.mp4',sourceVersion:1,playIntent:true});
  // The src setter pauses the actual decoder even though the transferred native intent is playing.
  v._native.paused=true;h.command(id,'attach',{sourceVersion:2});
  h.command(id,'sync',{time:12,readyState:4,paused:false});
  h.command(id,'detach',{sourceVersion:2,failed:true,resume:true,time:12});
  assert.equal(v.paused,false);assert.equal(pauses,0);
  v.pause();assert.equal(v.paused,true);assert.equal(pauses,1);
  assert.equal(h.events('stop').at(-1).reason,'pause');
});
