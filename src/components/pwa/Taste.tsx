"use client";

import { useEffect,useMemo,useState } from "react";
import { taste,useTaste } from "@/lib/pwa/taste";
import { messageOf } from "@/lib/pwa/storage";
import { PageScaffold } from "./Account";

export function TasteScreen({onBack}:{onBack:() => void}) {
  const state=useTaste(),[error,setError]=useState("");
  useEffect(() => {void taste.libraryTracks().catch(error => setError(messageOf(error)));},[]);
  const behavior=useMemo(() => taste.behavior(),[state.revision]),artists=useMemo(() => taste.topLibraryArtists().slice(0,8),[state.tracks]);
  const profile=state.profile;
  return <PageScaffold title="音乐口味" artistic cover="/pwa/artwork/login-music-classics.png" onBack={onBack}>
    <p className="pipo-taste-intro">从对话、收藏与聆听，持续了解你</p>
    <section className="pipo-taste-unified"><h2>你的口味库</h2><p style={{marginBottom:16}}>本次点歌要求始终优先。长期偏好帮助选歌，近期情绪随聆听变化。</p>
      <h3>你明确说过</h3>{!state.explicit.length && <p>还没有记录长期偏好。你可以告诉 AI 喜欢或不喜欢什么；一次点歌不会被直接记作长期喜好。</p>}
      {[...state.explicit].reverse().map(fact => <div className="pipo-taste-fact" key={fact.key}><div><strong>{fact.liked?"偏爱":"尽量避开"} · {fact.value}</strong><small>对话 · {new Date(fact.updatedAt).getMonth()+1}/{new Date(fact.updatedAt).getDate()} · “{fact.evidence}”</small></div><button onClick={() => {void taste.forget(fact.key).catch(error => setError(messageOf(error)));}}>忘记</button></div>)}
      <h3 style={{marginTop:12}}>最近的聆听</h3><p>{behavior.brief || (state.ready?"继续聆听，听完、重听和跳过会逐渐形成近期倾向。":"正在整理聆听记录…")}</p>{behavior.confidence>=.18 && <small>依据 {state.events.length} 条播放记录 · 近期信号会随时间减弱</small>}
      {!!state.tracks.length && <><h3 style={{marginTop:16}}>收藏中的音乐人</h3><p>来自资料库 {state.tracks.length} 首歌曲，作为探索起点</p><Chips values={artists.map(artist => artist.name)} /></>}
    </section>
    {error && <p className="pipo-error" role="alert">{error}</p>}
    {!profile ? <div className="pipo-taste-empty"><h2>还没有音乐画像</h2><p>你明确表达的偏好和近期聆听记录仍可在上方查看。</p></div> : <>
      <p className="pipo-taste-label">你的音乐画像</p><h2 className="pipo-taste-summary">{profile.summary}</h2><p className="pipo-taste-origin">来自 {profile.sourcePlaylistCount} 张歌单 · {profile.sampledTrackCount} 首分析样本</p>
      <section className="pipo-taste-section"><h2>流派倾向</h2>{profile.genres.map(genre => <div className="pipo-taste-genre" key={genre.tag}><div><strong>{genre.tag}</strong><small>{Math.trunc(genre.weight*100)}%</small></div><div className="pipo-taste-bar"><i style={{width:`${genre.weight*100}%`}} /></div>{!!genre.examples.length && <p>{genre.examples.join(" · ")}</p>}</div>)}</section>
      {!!profile.moods.length && <section className="pipo-taste-section"><h2>情绪关键词</h2><Chips values={profile.moods} /></section>}
      {!!profile.topArtists.length && <section className="pipo-taste-section"><h2>常听艺人</h2>{profile.topArtists.map(artist => <div className="pipo-taste-artist" key={artist.name}><strong>{artist.name}</strong><div className="pipo-taste-bar"><i style={{width:`${artist.affinity*100}%`}} /></div><small>{Math.trunc(artist.affinity*100)}</small></div>)}</section>}
    </>}
  </PageScaffold>;
}
function Chips({values}:{values:string[]}) {return <div className="pipo-taste-chips">{values.map(value => <span key={value}>{value}</span>)}</div>;}
