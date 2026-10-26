"use client";

import { useEffect,useRef } from "react";
import { randomId } from "./id";

type Layer={id:string;back:() => boolean;state:Record<string,unknown>;popped:boolean;replaceOnRoute:boolean};
const layers:Layer[]=[];let listening=false,ignoredPops=0;
const pendingCloses=new Map<string,ReturnType<typeof setTimeout>>();
function install() {
  if(listening) return;listening=true;
  window.addEventListener("popstate",event => {
    if(ignoredPops) {ignoredPops--;event.stopImmediatePropagation();return;}
    const layer=layers.at(-1);if(!layer || event.state?.pipoLayer===layer.id) return;
    event.stopImmediatePropagation();layer.popped=true;
    if(!layer.back()) {layer.popped=false;history.pushState(layer.state,"",location.href);}
  },true);
}
/** A dialog/search layer consumes Back before the persistent page route. */
export function useBackLayer(active:boolean,onBack:() => boolean|void,replaceOnRoute=true) {
  const id=useRef(""),callback=useRef(onBack);callback.current=onBack;
  useEffect(() => {
    if(!active) return;install();id.current ||= randomId();
    clearTimeout(pendingCloses.get(id.current));pendingCloses.delete(id.current);
    const state={...history.state,pipoLayer:id.current};
    if(history.state?.pipoLayer!==id.current) history.pushState(state,"",location.href);
    const layer:Layer={id:id.current,state,popped:false,replaceOnRoute,back:() => callback.current()!==false};layers.push(layer);
    return () => {const index=layers.indexOf(layer);if(index>=0) layers.splice(index,1);if(layer.popped) return;pendingCloses.set(layer.id,setTimeout(()=>{pendingCloses.delete(layer.id);if(history.state?.pipoLayer===layer.id) {ignoredPops++;history.back();}},0));};
  },[active,replaceOnRoute]);
}
export function routeHistory(state:Record<string,unknown>,replace=false) {
  const value={...history.state,...state,pipoLayer:undefined};
  if(replace || layers.some(layer => layer.replaceOnRoute && layer.id===history.state?.pipoLayer)) history.replaceState(value,"",location.href);
  else history.pushState(value,"",location.href);
}
