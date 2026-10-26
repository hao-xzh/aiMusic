"use client";

import { useCallback, useEffect, useLayoutEffect, useRef, useState, type CSSProperties } from "react";
import { Browse, TrackActions, type TrackActionContext } from "./Browse";
import { Player, PlayerSurface, MiniPlayer, QueueSheet } from "./Player";
import { Login, Settings } from "./Account";
import { TasteScreen } from "./Taste";
import { NativeAssistant } from "./NativeAssistant";
import { Icon } from "./Icon";
import { player, usePlayerState, type ContinuousSource } from "@/lib/pwa/player";
import { library, useLibrary } from "@/lib/pwa/library";
import { recommendations, useHome } from "@/lib/pwa/recommend";
import { preferences, usePreferences } from "@/lib/pwa/settings";
import { taste, aiReady } from "@/lib/pwa/taste";
import { assistant } from "@/lib/pwa/assistant";
import { analyzeTrack } from "@/lib/pwa/music-analysis";
import { diagnostic } from "@/lib/pwa/diagnostics";
import { messageOf } from "@/lib/pwa/storage";
import type { NativeTrack } from "@/lib/pwa/model";
import { routeHistory,useBackLayer } from "@/lib/pwa/back-navigation";
import { cssRgb,paletteSurfaces,usePalette } from "@/lib/pwa/palette";
import "./native.css";
import "./player.css";
import "./account.css";
import "./taste.css";
import "./assistant.css";
import "./app.css";

type Route="browse"|"player"|"settings"|"aiSettings"|"taste"|"login";
const routes:Route[]=["browse","player","settings","aiSettings","taste","login"];
const homeSource:ContinuousSource={fetch:async excluded=>(await recommendations.feed(excluded,8,undefined,true)).tracks,accepts:recommendations.accepts};
const radioSource:ContinuousSource={fetch:async excluded=>(await recommendations.feed(excluded,8,player.snapshot().current||undefined)).tracks,accepts:recommendations.accepts};

export function NativeApp() {
  const [stack,setStack]=useState<Route[]>(["browse"]), [tab,setTab]=useState<"home"|"library">("home"), [immersive,setImmersive]=useState(false);
  const [aiOpen,setAiOpen]=useState(false), [aiKeyboard,setAiKeyboard]=useState(true), [aiRequired,setAiRequired]=useState(false), [queueOpen,setQueueOpen]=useState(false), [creating,setCreating]=useState(false);
  const [action,setAction]=useState<{track:NativeTrack;context?:TrackActionContext}|null>(null), [keyboard,setKeyboard]=useState(0), [error,setError]=useState("");
  const playback=usePlayerState(), account=useLibrary(), home=useHome(), {settings}=usePreferences();
  const root=useRef<HTMLDivElement>(null), waterPage=useRef<HTMLDivElement>(null), audio=useRef<HTMLAudioElement>(null), mini=useRef<HTMLDivElement>(null), miniDock=useRef<HTMLDivElement>(null), navigation=useRef<HTMLElement>(null);
  const currentStack=useRef(stack);currentStack.current=stack;
  const returningTo=useRef<Route[]|null>(null);
  const route=stack[stack.length-1], playerExpanded=stack.includes("player");
  const palette=usePalette(playback.current?.artworkUrl);
  const edgeColor=route==="player"?cssRgb(paletteSurfaces(palette).surface):"#070b12";
  const hideNav=route==="login"||route==="aiSettings"||keyboard>80||creating;
  const showMini=!!playback.current&&!hideNav;
  const navigate=useCallback((destination:Route)=>{
    const old=currentStack.current;if(old[old.length-1]===destination) return;
    returningTo.current=null;
    const next=[...old,destination];
    currentStack.current=next;setStack(next);routeHistory({pipoRoutes:next});
  },[]);
  const openPlayer=useCallback(()=>navigate("player"),[navigate]);
  const goBack=useCallback(()=>{
    if(returningTo.current) return;
    if(currentStack.current.length>1) {returningTo.current=currentStack.current.slice(0,-1);history.back();}
    else {setStack(["browse"]);setTab("home");}
  },[]);
  const openAi=useCallback(()=>{setAiKeyboard(true);setAiOpen(true);},[]);
  const recommendMusic=useCallback(()=>{
    setAiKeyboard(false);setAiOpen(true);
    if(!aiReady()) setAiRequired(true);
    else void assistant.submit("根据我的听歌偏好，为我挑选并播放几首适合现在听的歌；如果偏好信息不足，先推荐一些耐听的歌曲。");
  },[]);
  const updateCreate=useCallback((visible:boolean)=>setCreating(visible),[]);
  useBackLayer(aiRequired,() => setAiRequired(false));
  useEffect(()=>{
    const previous=history.state?.pipoRoutes;
    if(Array.isArray(previous)&&previous.length&&previous.every(value=>routes.includes(value))) {setStack(previous);currentStack.current=previous;}
    else history.replaceState({...history.state,pipoRoutes:["browse"]},"",location.href);
    // Transient sheets are closed on reload, so discard their history entries before restoring page navigation.
    let restoringLayers=!!history.state?.pipoLayer;
    const pop=(event:PopStateEvent)=>{
      if(restoringLayers&&event.state?.pipoLayer) {history.back();return;}
      restoringLayers=false;
      const next:Route[]=Array.isArray(event.state?.pipoRoutes)?event.state.pipoRoutes.filter((value:Route)=>routes.includes(value)):["browse"];
      const target=returningTo.current;
      // A dismissed overlay may still have an entry carrying the current route stack.
      if(target&&next.length>target.length&&target.every((value,index)=>next[index]===value)) {history.back();return;}
      returningTo.current=null;setAiOpen(false);setQueueOpen(false);setAction(null);setImmersive(false);
      currentStack.current=next.length?next:["browse"];setStack(currentStack.current);
    };
    window.addEventListener("popstate",pop);if(restoringLayers) history.back();return()=>window.removeEventListener("popstate",pop);
  },[]);
  useEffect(()=>{
    preferences.load();const detach=audio.current?player.attach(audio.current):()=>undefined;
    player.setFallbackSource(radioSource);const listening=player.onListening(event=>{taste.record(event);diagnostic("playback",event.type,{id:event.track.id,listenedMs:event.listenedMs,durationMs:event.durationMs});});
    const likes=library.onFavorite(recommendations.favorite);
    void preferences.refreshAi().catch(error=>diagnostic("ai","config_load_failed",{message:messageOf(error)}));
    void library.refreshAccount();const stop=assistant.start(openPlayer);
    const online=()=>{void library.refreshAccount();void preferences.refreshAi().catch(()=>undefined);};window.addEventListener("online",online);
    return()=>{stop();listening();likes();detach();window.removeEventListener("online",online);};
  },[openPlayer]);
  useEffect(()=>{
    if(!account.ready) return;let cancelled=false;
    void (async()=>{await taste.load(account.account?.userId);if(cancelled) return;await recommendations.load(account.account?.userId);})().catch(error=>{if(!cancelled) setError(messageOf(error));});
    return()=>{cancelled=true;};
  },[account.ready,account.account?.userId]);
  useEffect(()=>{
    if(!playback.current||!playback.playing||!navigator.onLine) return;
    const tracks=[playback.current,player.upcoming()].filter((track):track is NativeTrack=>!!track), timer=setTimeout(()=>{for(const track of tracks) void analyzeTrack(track).catch(error=>diagnostic("analysis","track_failed",{id:track.id,message:messageOf(error)}));},1200);
    return()=>clearTimeout(timer);
  },[playback.current?.id,playback.playing,playback.queue,playback.mode]);
  useLayoutEffect(()=>{
    const elements=[document.documentElement,document.body];
    const previous=elements.map(element=>({background:element.style.backgroundColor,transition:element.style.transition}));
    const theme=document.querySelector<HTMLMetaElement>('meta[name="theme-color"]'),previousTheme=theme?.content;
    for(const element of elements) {element.style.backgroundColor=edgeColor;element.style.transition="background-color 1100ms cubic-bezier(.32,.72,0,1)";}
    if(theme) theme.content=edgeColor;
    return()=>{elements.forEach((element,index)=>{element.style.backgroundColor=previous[index].background;element.style.transition=previous[index].transition;});if(theme&&previousTheme!==undefined) theme.content=previousTheme;};
  },[edgeColor]);
  useLayoutEffect(()=>{
    const viewport=visualViewport;if(!viewport) return;
    const measure=()=>{const focused=document.activeElement,editing=focused instanceof HTMLElement&&(focused.matches("input,textarea")||focused.isContentEditable);const occluded=Math.max(0,(root.current?.clientHeight||innerHeight)-viewport.height-viewport.offsetTop);setKeyboard(editing&&occluded>80?occluded:0);root.current?.style.setProperty("--visual-top",`${viewport.offsetTop}px`);};
    const observer=new ResizeObserver(measure);if(root.current) observer.observe(root.current);
    viewport.addEventListener("resize",measure);viewport.addEventListener("scroll",measure);document.addEventListener("focusin",measure);document.addEventListener("focusout",measure);measure();
    return()=>{observer.disconnect();viewport.removeEventListener("resize",measure);viewport.removeEventListener("scroll",measure);document.removeEventListener("focusin",measure);document.removeEventListener("focusout",measure);};
  },[]);
  useEffect(()=>{
    const preventGesture=(event:Event)=>event.preventDefault();
    const preventPinch=(event:TouchEvent)=>{if(event.touches.length>1) event.preventDefault();};
    document.addEventListener("gesturestart",preventGesture,{passive:false});
    document.addEventListener("gesturechange",preventGesture,{passive:false});
    document.addEventListener("touchmove",preventPinch,{passive:false});
    return()=>{document.removeEventListener("gesturestart",preventGesture);document.removeEventListener("gesturechange",preventGesture);document.removeEventListener("touchmove",preventPinch);};
  },[]);
  useLayoutEffect(()=>{
    const measure=()=>{root.current?.style.setProperty("--mini-inset",`${miniDock.current?.getBoundingClientRect().height||0}px`);root.current?.style.setProperty("--nav-inset",`${navigation.current?.getBoundingClientRect().height||0}px`);};measure();
    const observer=new ResizeObserver(measure);if(miniDock.current) observer.observe(miniDock.current);if(navigation.current) observer.observe(navigation.current);return()=>observer.disconnect();
  },[showMini,hideNav]);
  useEffect(()=>{
    if(process.env.NODE_ENV!=="production"||!isSecureContext||!("serviceWorker" in navigator)) return;
    let cancelled=false;
    void navigator.serviceWorker.register("/pwa-sw.js",{scope:"/pwa"}).then(async()=>{
      const registration=await navigator.serviceWorker.ready;if(cancelled) return;
      const urls=[...new Set([...performance.getEntriesByType("resource").map(entry=>entry.name),...Array.from(document.querySelectorAll<HTMLScriptElement>("script[src]")).map(script=>script.src),...Array.from(document.querySelectorAll<HTMLLinkElement>('link[rel="stylesheet"]')).map(link=>link.href)])].filter(url=>{const parsed=new URL(url,location.href);return parsed.origin===location.origin&&parsed.pathname.startsWith("/_next/static/");});
      registration.active?.postMessage({type:"CACHE_URLS",urls});
    }).catch(error=>diagnostic("pwa","service_worker_failed",{message:messageOf(error)}));
    return()=>{cancelled=true;};
  },[]);
  const playTracks=(tracks:NativeTrack[],source:"home"|"search"|"playlist")=>{player.playQueue(tracks,source==="home"?homeSource:undefined);setImmersive(false);openPlayer();};
  const selectTab=(value:"home"|"library")=>{setTab(value);if(route!=="browse") {const next:Route[]=["browse"];currentStack.current=next;setStack(next);routeHistory({pipoRoutes:next},true);}};
  const clearMemory=async()=>{await assistant.clear();await taste.clear();};
  return <div ref={root} className="pipo-native" data-route={route} data-keyboard={keyboard>80} style={{"--keyboard-inset":`${keyboard}px`} as CSSProperties}>
    <audio ref={audio} className="pipo-audio" aria-hidden="true"/>
    <div className="pipo-water-page" ref={waterPage}><div className="pipo-scene" data-ai-open={aiOpen} inert={aiOpen||queueOpen||!!action||aiRequired}>
      <div className="pipo-page-slot">
        <div className="pipo-browse-persistent" aria-hidden={route!=="browse"} inert={route!=="browse"}>
          <Browse active={route==="browse"} tab={tab} onTab={setTab} searchRequest={0} current={playback.current?.id} home={home} refreshHome={()=>{void recommendations.refresh();}} undoDismissal={recommendations.undo} onProfile={()=>navigate("settings")} onLogin={()=>navigate("login")} onAi={recommendMusic} onPlay={playTracks} onMore={(track,context)=>setAction({track,context})} onCreateVisible={updateCreate}/>
        </div>
        {route!=="browse"&&route!=="player"&&<div className="pipo-route-page" key={route}>
          {route==="settings"||route==="aiSettings"?<Settings aiOnly={route==="aiSettings"} onBack={goBack} onLogin={()=>navigate("login")} onTaste={()=>navigate("taste")} onAiSettings={()=>navigate("aiSettings")} onClearMemory={clearMemory}/>:route==="login"?<Login onBack={goBack}/>:<TasteScreen onBack={goBack}/>}
        </div>}
        {showMini&&<div ref={miniDock} className="pipo-mini-dock" style={{visibility:route==="player"?"hidden":"visible"}}><MiniPlayer miniRef={mini} onOpen={openPlayer} onQueue={()=>setQueueOpen(true)} active={route!=="player"&&!aiOpen}/></div>}
      </div>
      {!hideNav&&<nav ref={navigation} className="pipo-bottom-nav" aria-label="主导航" aria-hidden={route==="player"} inert={route==="player"}><button aria-selected={tab==="home"} onClick={()=>selectTab("home")}><Icon name="Home" size={26}/>首页</button><button aria-selected={tab==="library"} onClick={()=>selectTab("library")}><Icon name="LibraryMusic" size={26}/>资料库</button></nav>}
      <PlayerSurface expanded={playerExpanded} visible={route==="player"} canCollapse={route==="player"&&!immersive} miniRef={mini} onCollapse={()=>{if(immersive&&(root.current?.clientWidth||0)<=(root.current?.clientHeight||0)) setImmersive(false);else {setImmersive(false);goBack();}}} onSettings={()=>navigate("settings")} onAi={openAi} compact={<MiniPlayer onOpen={openPlayer} onQueue={()=>setQueueOpen(true)} active={false}/>}>
        <Player visible={route==="player"&&!aiOpen} onQueue={()=>setQueueOpen(true)} onTaste={()=>navigate("taste")} immersive={immersive} onImmersive={setImmersive} showTranslation={settings.lyricTranslation} onTranslation={()=>preferences.update({lyricTranslation:!settings.lyricTranslation})}/>
      </PlayerSurface>
    </div></div>
    <NativeAssistant open={aiOpen} requestKeyboard={aiKeyboard} onOpen={openAi} onClose={()=>setAiOpen(false)} onSettings={()=>{setAiOpen(false);navigate("aiSettings");}} pageRef={waterPage} showDecorations={route==="player"&&!immersive} keyboard={keyboard}/>
    {queueOpen&&<QueueSheet onClose={()=>setQueueOpen(false)}/>}
    {action&&<TrackActions track={action.track} context={action.context} onClose={()=>setAction(null)} onQueueNext={()=>player.enqueueNext(action.track)} onLogin={()=>navigate("login")} onDismiss={()=>recommendations.dismiss(action.track)}/>}
    {aiRequired&&<div className="pipo-alert-scrim"><div role="alertdialog" aria-modal="true" aria-labelledby="pipo-ai-needed" className="pipo-alert"><h2 id="pipo-ai-needed">先连接 AI，再为你选歌</h2><p>请选择 AI 服务商并填写 API Key。配置完成后，回到搜索页点击选歌条，即可按你的听歌偏好选歌并播放。</p><div><button onClick={()=>setAiRequired(false)}>稍后</button><button onClick={()=>{setAiRequired(false);setAiOpen(false);navigate("aiSettings");}}>去配置 AI</button></div></div></div>}
    {error&&<div className="pipo-app-error" role="alert"><span>{error}</span><button onClick={()=>setError("")}>关闭</button></div>}
  </div>;
}
