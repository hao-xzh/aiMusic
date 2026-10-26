"use client";

import { aiChat,taste } from "./taste";
import { preferences } from "./settings";
import { greetingPrompts } from "./greetings";
import { readPreference,writePreference } from "./storage";

let greeting:{day:string;promise:Promise<string|null>}|undefined;
export function compactCaption(text:string) {const value=text.replace(/\s+/g," ").trim().replace(/^[「」『』"'“”\-— ]+|[「」『』"'“”\-— ]+$/g,"");return value.length<=120?value:`${value.slice(0,119).trimEnd()}…`;}
export function dailyGreeting() {
  const now=new Date(),day=[now.getFullYear(),now.getMonth()+1,now.getDate()].join("-");
  if(readPreference("greetedDay","")===day) return Promise.resolve(null);
  if(greeting?.day===day) return greeting.promise;
  const {settings}=preferences.snapshot(),profile=taste.snapshot().profile,dow=now.getDay(),hour=now.getHours();
  const weekday=["周日","周一","周二","周三","周四","周五","周六"][dow],period=hour>=5&&hour<=10?"上午":hour>=11&&hour<=13?"中午":hour>=14&&hour<=17?"下午":hour>=18&&hour<=21?"傍晚":hour>=22||hour===0?"深夜":"凌晨";
  const context=`${weekday}${period}${dow===0||dow===6?"（周末）":dow===5?" · 明天就周末":dow===4?" · 还有两天周末":""}`;
  const user=[`当下:${context}`,profile?.summary?`TA 的音乐画像:${profile.summary.slice(0,650)}`:"",settings.userFacts?`TA 的自述:${settings.userFacts.slice(0,120)}`:""].filter(Boolean).join("\n");
  const promise=aiChat(greetingPrompts[settings.personaId] || greetingPrompts.toxic,user,.95,80).then(result => {const text=compactCaption(result).slice(0,80);if(text) writePreference("greetedDay",day);return text || null;}).catch(() => null);
  greeting={day,promise};return promise;
}
