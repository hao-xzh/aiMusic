"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { library, useLibrary } from "@/lib/pwa/library";
import { repository, type NativePlaylist, type NativeTrack } from "@/lib/pwa/model";
import { messageOf, readPreference, writePreference } from "@/lib/pwa/storage";
import { Artwork, Backdrop, IconButton, Sheet, TrackList } from "./Shared";
import { Icon } from "./Icon";
import { useBackLayer } from "@/lib/pwa/back-navigation";

export type HomeState = { tracks:NativeTrack[]; caption:string; message:string; loading:boolean; error:string; dismissed:NativeTrack | null };
export type TrackActionContext = { fromHome?:boolean; playlist?:NativePlaylist };
type Props = {
  active:boolean;
  tab:"home" | "library"; onTab:(tab:"home" | "library") => void; searchRequest:number;
  current?:string; home:HomeState; refreshHome:() => void; undoDismissal:() => void;
  onProfile:() => void; onLogin:() => void; onAi:() => void;
  onPlay:(tracks:NativeTrack[],source:"home" | "search" | "playlist") => void;
  onMore:(track:NativeTrack,context?:TrackActionContext) => void;
  onCreateVisible:(visible:boolean) => void;
};
const loginCover = "/pwa/artwork/login-music-classics.png";
const cloudPlaylist:NativePlaylist = { id:-1,name:"我的网盘",trackCount:0 };
export function Browse(props:Props) {
  const state = useLibrary();
  const [detail,setDetail] = useState<NativePlaylist | null>(null);
  const [searchOpen,setSearchOpen] = useState(false);
  const [query,setQuery] = useState(""); const [results,setResults] = useState<NativeTrack[]>([]);
  const [submitted,setSubmitted] = useState(false); const [searchBusy,setSearchBusy] = useState(false); const [searchError,setSearchError] = useState("");
  const [grid,setGrid] = useState(false); const [filter,setFilter] = useState("全部"); const [sort,setSort] = useState("recent"); const [creating,setCreating] = useState(false);
  const homeScroll = useRef<HTMLDivElement>(null); const libraryScroll = useRef<HTMLDivElement>(null); const searchScroll = useRef<HTMLDivElement>(null);
  const searchController = useRef<AbortController | null>(null); const searchInput = useRef<HTMLInputElement>(null); const previousRequest = useRef(0);
  useEffect(() => { setGrid(readPreference("library.grid",false)); setSort(readPreference("library.sort","recent")); },[]);
  useEffect(() => { setDetail(null); },[state.account?.userId]);
  useEffect(() => { props.onCreateVisible(creating); return () => props.onCreateVisible(false); },[creating,props.onCreateVisible]);
  const editQuery = (value:string) => { searchController.current?.abort(); setQuery(value); setResults([]); setSubmitted(false); setSearchBusy(false); setSearchError(""); };
  const openSearch = () => { props.onTab("library"); setDetail(null); setSearchOpen(true); };
  useEffect(() => { if (props.searchRequest !== previousRequest.current) { previousRequest.current = props.searchRequest; setDetail(null); setSearchOpen(true); } },[props.searchRequest]);
  useEffect(() => { if (searchOpen) searchInput.current?.focus(); else searchController.current?.abort(); },[searchOpen]);
  useEffect(() => () => searchController.current?.abort(),[]);
  const search = async () => {
    const keyword = query.trim(); if (!keyword) return;
    searchController.current?.abort(); const controller = new AbortController(); searchController.current = controller;
    setSubmitted(true); setSearchBusy(true); setSearchError(""); searchInput.current?.blur();
    try { const found = await repository.search(keyword,100,0,controller.signal); if (!controller.signal.aborted) setResults(found); }
    catch { if (!controller.signal.aborted) setSearchError("暂时无法搜索，请检查网络后重试"); }
    finally { if (!controller.signal.aborted) setSearchBusy(false); }
  };
  const cancelSearch = () => { editQuery(""); searchInput.current?.blur(); setSearchOpen(false); };
  useBackLayer(props.active && (searchOpen || detail!==null),() => {if(detail) setDetail(null);else cancelSearch();},false);
  const playlists = useMemo(() => [...state.playlists].sort((a,b) => sort === "name" ? a.name.localeCompare(b.name,"zh-CN") : sort === "count" ? b.trackCount-a.trackCount : (b.updateTime || 0)-(a.updateTime || 0)),[state.playlists,sort]);
  const brand = <div className="pipo-brand"><strong>PIPO</strong><IconButton name="search" label="搜索" onClick={openSearch} /><IconButton name="profile" size={26} label="个人设置" onClick={props.onProfile} /></div>;
  const openPlaylist = (playlist:NativePlaylist) => { setDetail(playlist); cancelSearch(); };
  const playHome = (index:number) => { const tracks = props.home.tracks; props.onPlay([...tracks.slice(index),...tracks.slice(0,index)],"home"); };
  return <>
    <div className="pipo-backdrop-host" style={{ display:props.tab === "home" && !detail ? "flex" : "none" }}>
      <Backdrop url={props.home.tracks[0]?.artworkUrl || state.playlists[0]?.coverImgUrl || loginCover} />
      <div ref={homeScroll} className="pipo-scroll">
        {brand}<div className="pipo-home-spacer" /><h1 className="pipo-title">听点不一样</h1>
        <p className="pipo-home-caption">{props.home.caption || (props.home.tracks.length ? "这一批，慢慢听。" : props.home.loading ? "正在寻找与你合拍的音乐" : "下一首喜欢，等你发现。")}</p>
        {props.home.tracks[0] && <><p className="pipo-kicker">此刻，为你</p><div className="pipo-featured"><h2>{props.home.tracks[0].title}</h2><button className="pipo-filled-play" onClick={() => playHome(0)} aria-label="播放此刻推荐"><Icon name="PlayArrow" size={24} /></button></div></>}
        <div className="pipo-section-row pipo-recommend-heading"><h2>推荐</h2><button className="pipo-text-button" onClick={props.refreshHome} disabled={props.home.loading}>{props.home.loading ? <span className="pipo-spinner" /> : <Icon name="spark" size={16} />}{props.home.loading ? "寻找推荐" : "换一批"}</button></div>
        {props.home.error && <div className="pipo-notice"><p>{props.home.error}</p><button onClick={props.refreshHome}>重试</button></div>}
        {props.home.message && <p className="pipo-notice">{props.home.message}</p>}
        {props.home.dismissed && <div className="pipo-section-row"><p data-grow className="pipo-notice">已减少类似推荐</p><button className="pipo-text-button" onClick={props.undoDismissal}>撤销</button></div>}
        <TrackList tracks={props.home.tracks} scrollRef={homeScroll} current={props.current} home onPlay={(_,index) => playHome(index)} onMore={track => props.onMore(track,{ fromHome:true })} />
        {!state.account && <div className="pipo-notice"><p>登录网易云，让推荐更了解你的收藏。</p><button onClick={props.onLogin}>登录</button></div>}
      </div>
    </div>
    <div className="pipo-backdrop-host" style={{ display:props.tab === "library" && !detail ? "flex" : "none" }}>
      <div ref={libraryScroll} className="pipo-scroll" style={{ display:searchOpen ? "none" : "block" }}>
        {brand}<h1 className="pipo-title">资料库</h1><p className="pipo-subtitle">你的歌单，都在这里。</p>
        <div className="pipo-library-filters">{["全部","歌单","网盘"].map(value => <div key={value} className="pipo-filter" data-selected={filter === value}><button onClick={() => setFilter(value)}>{value}</button></div>)}<span className="pipo-filter-spacer" /><button className="pipo-icon-button pipo-view-button" aria-label="列表视图" aria-pressed={!grid} onClick={() => { setGrid(false); writePreference("library.grid",false); }}><Icon name="LibraryMusic" /></button><button className="pipo-icon-button pipo-view-button" aria-label="网格视图" aria-pressed={grid} onClick={() => { setGrid(true); writePreference("library.grid",true); }}><Icon name="GridView" /></button></div>
        <div className="pipo-sort-row"><select className="pipo-sort" aria-label="歌单排序" value={sort} onChange={event => { setSort(event.target.value); writePreference("library.sort",event.target.value); }}><option value="recent">最近更新</option><option value="name">名称</option><option value="count">曲目数</option></select><div style={{ display:"flex" }}><IconButton name="Refresh" label="刷新资料库" disabled={state.loading} onClick={() => { void library.refresh(); }} /><IconButton name="Add" label="新建歌单" onClick={() => state.account ? setCreating(true) : props.onLogin()} /></div></div>
        {state.loading && <p className="pipo-notice"><span className="pipo-spinner" /> 正在更新资料库</p>}
        {state.error && <div className="pipo-notice"><p>{state.error}</p><button onClick={() => { void library.refreshAccount(); }}>重试</button></div>}
        {!state.account ? <div className="pipo-notice"><p>登录后查看你的歌单和网盘。</p><button onClick={props.onLogin}>登录网易云</button></div> : <>
          {filter !== "歌单" && <button className="pipo-playlist-row pipo-cloud-row" onClick={() => openPlaylist(cloudPlaylist)}><span className="pipo-artwork" style={{ width:48 }}><Icon name="Cloud" /></span><span className="pipo-playlist-copy"><strong>我的网盘</strong></span><Icon name="ChevronRight" /></button>}
          {filter !== "网盘" && (grid ? <div className="pipo-playlist-grid">{playlists.map(playlist => <button key={playlist.id} className="pipo-playlist-card" onClick={() => openPlaylist(playlist)}><Artwork url={playlist.coverImgUrl} /><strong>{playlist.name}</strong><small>{playlist.trackCount} 首</small></button>)}</div> : playlists.map(playlist => <PlaylistRow key={playlist.id} playlist={playlist} onClick={() => openPlaylist(playlist)} />))}
          {filter !== "网盘" && !playlists.length && !state.loading && <p className="pipo-notice">还没有歌单</p>}
        </>}
      </div>
      <div ref={searchScroll} className="pipo-scroll" style={{ display:searchOpen ? "block" : "none" }}>
        <div className="pipo-brand"><strong>PIPO</strong><IconButton name="profile" size={26} label="个人设置" onClick={props.onProfile} /></div>
        <div className="pipo-section-row"><h1 className="pipo-title">资料库</h1><button className="pipo-text-button pipo-ink" onClick={cancelSearch}>取消</button></div>
        <form className="pipo-search-form" onSubmit={event => { event.preventDefault(); void search(); }}><div className="pipo-search"><Icon name="search" size={20} /><input ref={searchInput} type="search" enterKeyHint="search" placeholder="搜索歌曲或歌手名" aria-label="搜索歌曲或歌手名" value={query} onChange={event => editQuery(event.target.value)} />{query && <IconButton name="close" size={18} label="清空搜索" onClick={() => editQuery("")} />}</div></form>
        {!submitted && <button className="pipo-search-ai" onClick={()=>{searchInput.current?.blur();props.onAi();}}>还没想好听什么？让 PIPO 帮你选歌</button>}
        {searchBusy && <p className="pipo-notice"><span className="pipo-spinner" /> 搜索中</p>}
        {searchError && <div className="pipo-notice"><p>{searchError}</p><button onClick={() => { void search(); }}>重试</button></div>}
        {submitted && !searchBusy && !searchError && !results.length && <p className="pipo-notice">没有找到歌曲，试试其他关键词。</p>}
        {!!results.length && <><div className="pipo-section-row"><h2>歌曲结果</h2><button className="pipo-text-button" onClick={() => props.onPlay(results,"search")}>播放全部 <Icon name="ChevronRight" size={18} /></button></div><TrackList tracks={results} scrollRef={searchScroll} current={props.current} onPlay={track => props.onPlay([track],"search")} onMore={track => props.onMore(track)} /></>}
      </div>
    </div>
    {detail && <PlaylistDetail key={`${state.account?.userId}:${detail.id}`} playlist={state.playlists.find(item => item.id === detail.id) || detail} current={props.current} onBack={() => setDetail(null)} onPlay={tracks => props.onPlay(tracks,"playlist")} onMore={track => props.onMore(track,{ playlist:detail })} revision={state.revision} />}
    {creating && <CreatePlaylist onClose={() => setCreating(false)} onCreated={playlist => { setCreating(false); openPlaylist(playlist); }} />}
  </>;
}

export function PlaylistRow({ playlist,onClick }: { playlist:NativePlaylist; onClick:() => void }) {
  return <button className="pipo-playlist-row" onClick={onClick}><Artwork url={playlist.coverImgUrl} /><span className="pipo-playlist-copy"><strong>{playlist.name}</strong><span>{playlist.trackCount} 首</span></span><Icon name="ChevronRight" /></button>;
}
function PlaylistDetail({ playlist,current,onBack,onPlay,onMore,revision }: { playlist:NativePlaylist; current?:string; onBack:() => void; onPlay:(tracks:NativeTrack[]) => void; onMore:(track:NativeTrack) => void; revision:number }) {
  const [tracks,setTracks] = useState<NativeTrack[]>([]); const [loading,setLoading] = useState(true); const [error,setError] = useState(""); const [query,setQuery] = useState("");
  const scroll = useRef<HTMLDivElement>(null); const request = useRef(0);
  const refresh = useCallback(async () => {
    const id = ++request.current; setLoading(true); setError("");
    try { const loaded = await library.loadTracks(playlist.id); if (id === request.current) setTracks(loaded); }
    catch (error) { if (id === request.current) setError(messageOf(error)); }
    finally { if (id === request.current) setLoading(false); }
  },[playlist.id]);
  useEffect(() => {
    let active = true; void library.cachedTracks(playlist.id).then(cached => { if (active) { if (cached.length) setTracks(cached); if (navigator.onLine) void refresh(); else { setLoading(false); if (!cached.length) setError("当前离线，尚未缓存此歌单。"); } } });
    return () => { active = false; ++request.current; };
  },[playlist.id,refresh,revision]);
  const filtered = useMemo(() => { const term = query.trim().toLocaleLowerCase(); return term ? tracks.filter(track => `${track.title} ${track.artist}`.toLocaleLowerCase().includes(term)) : tracks; },[tracks,query]);
  const play = (track:NativeTrack) => onPlay([track,...filtered.filter(item => item.id !== track.id)]);
  return <div className="pipo-backdrop-host pipo-page-enter"><Backdrop url={playlist.coverImgUrl || tracks[0]?.artworkUrl || loginCover} height={420} overlay={.25} />
    <div ref={scroll} className="pipo-scroll"><div className="pipo-detail-toolbar"><IconButton name="ArrowBack" label="返回资料库" onClick={onBack} /><IconButton name="Refresh" label="刷新歌单" disabled={loading} onClick={() => { void refresh(); }} /></div><div className="pipo-detail-spacer" />
      <p className="pipo-kicker">{playlist.id === -1 ? "网易云 · 网盘" : "PIPO · 歌单"}</p><h1 className="pipo-detail-title">{playlist.name}</h1><p className="pipo-detail-count">{tracks.length || playlist.trackCount} 首歌曲</p>
      <div className="pipo-detail-play"><button className="pipo-primary" disabled={!tracks.length} onClick={() => onPlay(tracks)}><Icon name="PlayArrow" />播放全部</button></div>
      <div className="pipo-search"><Icon name="search" size={20} /><input type="search" placeholder="搜索歌单" aria-label="搜索歌单" value={query} onChange={event => setQuery(event.target.value)} /></div>
      {loading && <p className="pipo-notice"><span className="pipo-spinner" /> 正在加载歌曲</p>}{error && <div className="pipo-notice"><p>{error}</p><button onClick={() => { void refresh(); }}>重试</button></div>}
      {!loading && !error && !filtered.length && <p className="pipo-notice">{query ? "没有找到匹配的歌曲" : "歌单还没有歌曲"}</p>}
      <TrackList tracks={filtered} scrollRef={scroll} current={current} onPlay={play} onMore={onMore} />
    </div></div>;
}

function CreatePlaylist({ onClose,onCreated }: { onClose:() => void; onCreated:(playlist:NativePlaylist) => void }) {
  const [name,setName] = useState(""); const [busy,setBusy] = useState(false); const [error,setError] = useState("");
  const [created,setCreated]=useState<NativePlaylist|null>(null);
  const create = async () => { if (!name.trim() || busy || created) return; setBusy(true); setError(""); try { setCreated(await library.createPlaylist(name.trim())); } catch (error) { setError(messageOf(error)); } finally { setBusy(false); } };
  return <Sheet label="新建歌单" onClose={()=>created?onCreated(created):onClose()} closeRequested={!!created} busy={busy||!!created} className="pipo-create-sheet">{dismiss => <div className="pipo-sheet-content pipo-create-content"><div className="pipo-section-row"><h2>新建歌单</h2><button className="pipo-text-button" disabled={busy||!!created} onClick={dismiss}>取消</button></div><form onSubmit={event => { event.preventDefault(); void create(); }} style={{ display:"contents" }}><label className="pipo-outline-input"><span>歌单名称</span><input value={name} autoFocus maxLength={100} onChange={event => setName(event.target.value)} disabled={busy||!!created} enterKeyHint="done" /></label>{error && <p className="pipo-error">{error}</p>}<button className="pipo-primary" style={{ minHeight:52 }} disabled={!name.trim() || busy || !!created}>{busy ? "正在创建…" : "创建歌单"}</button></form><p className="pipo-notice" style={{ padding:0,fontSize:13 }}>创建后可从歌曲菜单添加音乐</p></div>}</Sheet>;
}

export function TrackActions({ track,context,onClose,onQueueNext,onLogin,onDismiss }: { track:NativeTrack; context?:TrackActionContext; onClose:() => void; onQueueNext:() => void; onLogin:() => void; onDismiss:() => void }) {
  const state = useLibrary(); const [adding,setAdding] = useState(false); const [busy,setBusy] = useState(false); const [error,setError] = useState("");
  const afterClose=useRef<(()=>void)|undefined>(undefined);
  const own = library.ownPlaylists(); const liked = !!track.neteaseId && state.favoriteIds?.has(track.neteaseId);
  const removable = context?.playlist && own.some(item => item.id === context.playlist?.id);
  const run = async (operation:() => Promise<void>,dismiss:() => void) => { if (busy) return; setBusy(true); setError(""); try { await operation(); dismiss(); } catch (error) { setError(messageOf(error)); } finally { setBusy(false); } };
  return <Sheet label={`${track.title}的歌曲菜单`} onClose={()=>{onClose();afterClose.current?.();}} busy={busy}>{dismiss => <div className="pipo-sheet-content" style={{ maxHeight:660 }}><div className="pipo-sheet-header"><Artwork url={track.artworkUrl} eager /><div className="pipo-sheet-header-copy"><h2>{track.title}</h2><p>{track.artist} · {track.album}</p></div><IconButton name="close" size={22} label="关闭歌曲菜单" disabled={busy} onClick={dismiss} /></div>
    {error && <p className="pipo-error" role="alert">{error}</p>}
    {adding ? <><div className="pipo-section-row"><h3>添加到歌单</h3><button className="pipo-text-button" disabled={busy} onClick={() => setAdding(false)}>返回</button></div>{own.length ? own.map(playlist => <PlaylistRow key={playlist.id} playlist={playlist} onClick={() => { void run(() => library.modifyPlaylist(playlist.id,"add",track),dismiss); }} />) : <p className="pipo-notice">还没有可添加的歌单，请先在资料库新建歌单。</p>}</> : <>
      <button className="pipo-action-row" disabled={busy} onClick={() => { onQueueNext(); dismiss(); }}><Icon name="PlaylistPlay" /><span>下一首播放</span></button>
      <button className="pipo-action-row" disabled={busy} onClick={() => { if (!state.account) { afterClose.current=onLogin;dismiss(); } else setAdding(true); }}><Icon name="playlistAdd" /><span>添加到歌单</span></button>
      <button className="pipo-action-row" disabled={busy || (!!state.account && (!state.favoriteIds || state.pendingLikes.has(track.neteaseId || 0)))} onClick={() => { if (!state.account) { afterClose.current=onLogin;dismiss(); } else void run(() => library.toggleLike(track),dismiss); }}><Icon name={liked ? "heartFilled" : "heart"} /><span>{!state.account ? "喜欢" : !state.favoriteIds ? "正在读取喜欢状态" : liked ? "取消喜欢" : "喜欢"}</span></button>
      {state.account && state.favoriteError && <div className="pipo-notice"><p>{state.favoriteError}</p><button disabled={state.favoriteLoading} onClick={() => { void library.refreshFavorites(); }}>重试喜欢状态</button></div>}
      {context?.fromHome && <button className="pipo-action-row" disabled={busy} onClick={() => { onDismiss(); dismiss(); }}><Icon name="RemoveCircleOutline" /><span>不感兴趣</span></button>}
      {removable && <button className="pipo-action-row pipo-danger" disabled={busy} onClick={() => { void run(() => library.modifyPlaylist(context!.playlist!.id,"del",track),dismiss); }}><Icon name="RemoveCircleOutline" /><span>从歌单移除</span></button>}
    </>}
    <button className="pipo-sheet-cancel" disabled={busy} onClick={dismiss}>取消</button></div>}</Sheet>;
}
