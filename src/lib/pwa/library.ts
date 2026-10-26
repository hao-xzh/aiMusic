"use client";

import { useSyncExternalStore } from "react";
import type { UserProfile } from "@/lib/tauri";
import { PwaConnectionError } from "./api";
import { repository, type NativePlaylist, type NativeTrack } from "./model";
import { messageOf, readPreference, readRecord, writePreference, writeRecord } from "./storage";

type Snapshot = {
  ready: boolean; account: UserProfile | null; playlists: NativePlaylist[]; loading: boolean; error: string;
  favoriteIds: ReadonlySet<number> | null; favoriteLoading: boolean; favoriteError: string; pendingLikes: ReadonlySet<number>; revision: number;
};
const initial: Snapshot = { ready:false, account:null, playlists:[], loading:false, error:"", favoriteIds:null, favoriteLoading:false, favoriteError:"", pendingLikes:new Set(), revision:0 };
type CachedTracks = { tracks: NativeTrack[]; updateTime?: number | null };
class Library {
  private state = initial;
  private listeners = new Set<() => void>();
  private favoriteListeners=new Set<(track:NativeTrack,liked:boolean)=>void>();
  private generation = 0;
  private accountJob: Promise<void> | undefined;
  private libraryJob: Promise<void> | undefined;
  private favoriteJob: Promise<void> | undefined;
  private tracks = new Map<number,CachedTracks>();
  private trackJobs = new Map<number,Promise<NativeTrack[]>>();
  private favoriteChanges = new Map<number,{ revision:number; like:boolean }>();
  private mutationRevision = 0;
  subscribe = (listener: () => void) => { this.listeners.add(listener); return () => { this.listeners.delete(listener); }; };
  onFavorite=(listener:(track:NativeTrack,liked:boolean)=>void)=>{this.favoriteListeners.add(listener);return()=>{this.favoriteListeners.delete(listener);};};
  snapshot = () => this.state;
  serverSnapshot = () => initial;
  private update(patch: Partial<Snapshot>) { this.state = { ...this.state, ...patch }; this.listeners.forEach(listener => listener()); }
  refreshAccount = (): Promise<void> => this.accountJob ||= this.loadAccount().finally(() => { this.accountJob = undefined; });
  private async loadAccount() {
    let account: UserProfile | null;
    let connectionError="";
    try { account = await repository.account(); }
    catch (error) {
      if (!this.state.ready && (error instanceof PwaConnectionError || !navigator.onLine)) {
        account = readPreference<UserProfile | null>("lastAccount",null);
        connectionError=account?"暂时无法连接服务，正在显示已缓存的资料库。":messageOf(error);
      }
      else { this.update({ ready:true, error:messageOf(error) }); return; }
    }
    if (!this.state.ready || account?.userId !== this.state.account?.userId) {
      ++this.generation; this.tracks.clear(); this.trackJobs.clear(); this.favoriteChanges.clear(); this.libraryJob = undefined; this.favoriteJob = undefined;
      this.update({ ...initial, ready:true, account, error:connectionError, revision:this.state.revision+1 });
      if(!connectionError) writePreference("lastAccount",account);
      const owner = this.generation;
      if (account) {
        try { const cached = await readRecord<NativePlaylist[]>(`library:${account.userId}`); if (owner === this.generation && cached) this.update({ playlists:cached }); }
        catch { /* A fresh network response can restore a missing cache. */ }
      }
    } else this.update({ ready:true, account, error:connectionError });
    if (navigator.onLine && !connectionError) await this.refresh();
  }
  ownPlaylists = () => this.state.playlists.filter(playlist => playlist.userId == null || playlist.userId === this.state.account?.userId);
  favoritePlaylist = () => { const own = this.ownPlaylists(); return own.find(item => item.specialType === 5) || own.find(item => item.name === "我喜欢的音乐" || item.name.endsWith("喜欢的音乐")); };
  refresh = (): Promise<void> => {
    if (!this.state.account) return Promise.resolve();
    if (this.libraryJob) return this.libraryJob;
    const owner = this.generation, uid = this.state.account.userId;
    this.update({ loading:true, error:"" });
    const job = (async () => {
      try {
        const playlists = await repository.playlists(uid); if (owner !== this.generation) return;
        const playlistsById=new Map<number,NativePlaylist>();
        for (const playlist of playlists) if (!playlistsById.has(playlist.id)) playlistsById.set(playlist.id,playlist);
        for (const [id,cached] of this.tracks) { if (id === -1) continue; const fresh = playlistsById.get(id); if (!fresh || fresh.updateTime !== cached.updateTime) this.tracks.delete(id); }
        this.update({ playlists, revision:this.state.revision+1 });
        await writeRecord(`library:${uid}`,playlists).catch(() => this.update({ error:"资料库缓存暂时无法保存" }));
        if (owner === this.generation) void this.refreshFavorites();
      } catch (error) { if (owner === this.generation) this.update({ error:messageOf(error) }); }
      finally { if (owner === this.generation) this.update({ loading:false }); }
    })();
    this.libraryJob = job; void job.finally(() => { if (this.libraryJob === job) this.libraryJob = undefined; }); return job;
  };
  refreshFavorites = (): Promise<void> => {
    if (this.favoriteJob) return this.favoriteJob;
    const playlist = this.favoritePlaylist(); if (!playlist || !this.state.account) { this.update({ favoriteError:"未找到喜欢的音乐歌单" }); return Promise.resolve(); }
    const owner = this.generation, revision = this.mutationRevision;
    this.update({ favoriteLoading:true, favoriteError:"" });
    const job = (async () => {
      try {
        const ids = new Set(await repository.playlistTrackIds(playlist.id)); if (owner !== this.generation) return;
        for (const [id,change] of this.favoriteChanges) { if (change.revision > revision) { if (change.like) ids.add(id); else ids.delete(id); } else this.favoriteChanges.delete(id); }
        this.update({ favoriteIds:ids });
      } catch (error) { if (owner === this.generation) this.update({ favoriteError:messageOf(error) }); }
      finally { if (owner === this.generation) this.update({ favoriteLoading:false }); }
    })();
    this.favoriteJob = job; void job.finally(() => { if (this.favoriteJob === job) this.favoriteJob = undefined; }); return job;
  };
  cachedTracks = async (id: number): Promise<NativeTrack[]> => {
    const inMemory = this.tracks.get(id); if (inMemory) return inMemory.tracks;
    const owner = this.generation, uid = this.state.account?.userId; if (!uid) return [];
    const cached = await readRecord<CachedTracks>(`tracks:${uid}:${id}`).catch(() => undefined);
    if (owner !== this.generation || !cached) return [];
    this.tracks.set(id,cached); return cached.tracks;
  };
  loadTracks = (id: number): Promise<NativeTrack[]> => {
    const running = this.trackJobs.get(id); if (running) return running;
    const owner = this.generation, uid = this.state.account?.userId;
    const job = (async () => {
      const tracks = id === -1 ? await repository.cloudTracks() : await repository.playlistTracks(id);
      if (owner !== this.generation) throw new Error("账号已切换，请重新打开歌单。");
      const cached = { tracks, updateTime:this.state.playlists.find(item => item.id === id)?.updateTime };
      this.tracks.set(id,cached); if (uid) await writeRecord(`tracks:${uid}:${id}`,cached).catch(() => undefined); return tracks;
    })();
    this.trackJobs.set(id,job); void job.then(() => { if (this.trackJobs.get(id) === job) this.trackJobs.delete(id); },() => { if (this.trackJobs.get(id) === job) this.trackJobs.delete(id); }); return job;
  };
  toggleLike = async (track: NativeTrack) => {
    const id = track.neteaseId; if (!id || !this.state.account) throw new Error("请先登录网易云。");
    if (!this.state.favoriteIds) throw new Error("喜欢状态尚未加载，请稍后重试。");
    if (this.state.pendingLikes.has(id)) return;
    const owner = this.generation, like = !this.state.favoriteIds.has(id);
    this.update({ pendingLikes:new Set([...this.state.pendingLikes,id]) });
    try {
      await repository.like(id,like); if (owner !== this.generation) return;
      const ids = new Set(this.state.favoriteIds); if (like) ids.add(id); else ids.delete(id);
      this.favoriteChanges.set(id,{ revision:++this.mutationRevision,like });
      const playlist = this.favoritePlaylist(); if (playlist) this.tracks.delete(playlist.id);
      this.update({ favoriteIds:ids,revision:this.state.revision+1 });
      this.favoriteListeners.forEach(listener=>listener(track,like));
    } finally { if (owner === this.generation) this.update({ pendingLikes:new Set([...this.state.pendingLikes].filter(value => value !== id)) }); }
  };
  modifyPlaylist = async (id: number, op: "add" | "del", track: NativeTrack) => {
    if (!track.neteaseId) throw new Error("当前歌曲没有可用的网易云标识。");
    const owner = this.generation; await repository.modifyPlaylist(id,op,[track.neteaseId]); if (owner !== this.generation) return;
    this.tracks.delete(id); this.update({ revision:this.state.revision+1 }); await this.refresh(); if (this.favoritePlaylist()?.id === id) await this.refreshFavorites();
  };
  createPlaylist = async (name: string) => {
    const id = await repository.createPlaylist(name); await this.refresh();
    return this.state.playlists.find(item => item.id === id) || { id,name,trackCount:0,userId:this.state.account?.userId };
  };
  logout = async () => { await repository.logout(); await this.refreshAccount(); };
}
export const library = new Library();
export function useLibrary() { return useSyncExternalStore(library.subscribe,library.snapshot,library.serverSnapshot); }
