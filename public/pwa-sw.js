const CACHE_NAME = "pipo-pwa-v3";
const AUDIO_CACHE = "pipo-native-audio-v1";
const META_CACHE = "pipo-native-meta-v1";
const ARTWORK_CACHE = "pipo-native-artwork-v1";
const FRAGMENT_CACHE = "pipo-native-fragments-v1";
let audioEpoch = 0;
const PWA_PATH = "/pwa";
const ICON_PATHS = new Set(["/icon-192.png", "/icon-512.png", "/pwa/manifest.webmanifest"]);
const PRECACHE_URLS = [PWA_PATH, ...ICON_PATHS];

function isPwaPath(pathname) {
  return pathname === PWA_PATH || pathname === `${PWA_PATH}/` || pathname === `${PWA_PATH}/probe`;
}

function isDevelopmentScript(pathname) {
  return (
    pathname.includes("/_next/static/development/") ||
    pathname.endsWith("/_next/static/chunks/webpack.js") ||
    pathname.endsWith("/_next/static/chunks/react-refresh.js") ||
    pathname.endsWith("/_next/static/chunks/main.js") ||
    pathname.endsWith("/_next/static/chunks/main-app.js") ||
    pathname.endsWith("/_next/static/chunks/app-pages-internals.js")
  );
}

function isStaticPath(pathname) {
  return (
    (pathname.startsWith("/_next/static/") && !isDevelopmentScript(pathname)) ||
    ICON_PATHS.has(pathname) || pathname.startsWith("/pwa/artwork/") || pathname.startsWith("/pwa/fonts/")
  );
}

function isAllowedUrl(url) {
  return url.origin === self.location.origin && (isPwaPath(url.pathname) || isStaticPath(url.pathname));
}

function allowedMessageUrls(values) {
  const urls = [];
  for (const value of new Set(values)) {
    if (typeof value !== "string") continue;
    try {
      const url = new URL(value, self.location.origin);
      if (isAllowedUrl(url)) urls.push(url.href);
    } catch {
      // 无效地址不进入缓存。
    }
  }
  return urls;
}

async function cacheNetworkResponse(cache, key, response) {
  if (response.ok) {
    try {
      await cache.put(key, response.clone());
    } catch {
      // 缓存不可用时仍返回当前网络响应。
    }
  }
  return response;
}

async function networkFirstPage(request) {
  const cache = await caches.open(CACHE_NAME);
  const key = new URL(request.url).pathname.replace(/\/$/, "");
  try {
    const response = await fetch(request);
    return await cacheNetworkResponse(cache, key, response);
  } catch {
    return (await cache.match(key)) || Response.error();
  }
}

async function cacheFirstStatic(request) {
  const cache = await caches.open(CACHE_NAME);
  const cached = await cache.match(request);
  if (cached) return cached;
  const response = await fetch(request);
  return cacheNetworkResponse(cache, request, response);
}

async function cacheUrls(urls) {
  const cache = await caches.open(CACHE_NAME);
  await Promise.all(
    urls.map(async (url) => {
      if (await cache.match(url)) return;
      const response = await fetch(url);
      if (!response.ok) throw new Error(`资源缓存失败：${response.status}`);
      await cache.put(url, response.clone());
    }),
  );
}

function reply(source, message) {
  if (source && "postMessage" in source) source.postMessage(message);
}

self.addEventListener("install", (event) => {
  event.waitUntil(
    caches
      .open(CACHE_NAME)
      .then((cache) => cache.addAll(PRECACHE_URLS))
      .then(() => self.skipWaiting()),
  );
});

self.addEventListener("activate", (event) => {
  event.waitUntil((async () => {for(const key of await caches.keys()) if(key.startsWith("pipo-pwa-") && key!==CACHE_NAME) await caches.delete(key);await self.clients.claim();})());
});

self.addEventListener("fetch", (event) => {
  const url = new URL(event.request.url);
  if (url.origin !== self.location.origin) return;

  if (event.request.method === "GET" && /^\/api\/pwa\/audio\/\d+$/.test(url.pathname)) {
    event.respondWith(audioResponse(event));
  } else if(event.request.method === "GET" && url.pathname === "/api/pwa/artwork") {
    event.respondWith((async () => {const cache=await caches.open(ARTWORK_CACHE),saved=await cache.match(event.request);if(saved) return saved;const response=await fetch(event.request);if(response.ok && /^image\//.test(response.headers.get("Content-Type") || "")) {event.waitUntil(cache.put(event.request,response.clone()).then(async () => {const keys=await cache.keys();await Promise.all(keys.slice(0,Math.max(0,keys.length-160)).map(key => cache.delete(key)));}).catch(() => undefined));}return response;})());
  } else if (event.request.mode === "navigate" && isPwaPath(url.pathname)) {
    event.respondWith(networkFirstPage(event.request));
  } else if (isStaticPath(url.pathname)) {
    event.respondWith(cacheFirstStatic(event.request));
  }
});

async function audioKey(request) {
  const url = new URL(request.url);
  url.searchParams.delete("download");
  return url.href;
}

async function audioResponse(event) {
  const epoch=audioEpoch;
  const request = event.request, key = await audioKey(request), cache = await caches.open(AUDIO_CACHE);
  const cached = await cache.match(key);
  if (cached) {
    const range = request.headers.get("Range");
    if (!range) return cached;
    const match = /^bytes=(\d*)-(\d*)$/.exec(range);
    if (match) {
      const blob = await cached.blob();
      const start = match[1] ? Number(match[1]) : Math.max(0, blob.size - Number(match[2]));
      const end = match[1] && match[2] ? Math.min(blob.size - 1, Number(match[2])) : blob.size - 1;
      if (start > end || start >= blob.size) return new Response(null, { status:416, headers:{"Content-Range":`bytes */${blob.size}`} });
      const headers = new Headers(cached.headers);
      headers.set("Content-Range",`bytes ${start}-${end}/${blob.size}`); headers.set("Content-Length",String(end-start+1)); headers.set("Accept-Ranges","bytes");
      return new Response(blob.slice(start,end+1,blob.type),{status:206,headers});
    }
  }
  const response = await fetch(request);
  // Cache only a complete encoded audio body. Safari's 0-1 byte probe is never mistaken for a song.
  const range = /^bytes (\d+)-(\d+)\/(\d+)$/.exec(response.headers.get("Content-Range") || "");
  const complete = response.status === 200 || (response.status === 206 && range && range[1] === "0" && Number(range[2])+1 === Number(range[3]));
  if (complete && /^(audio\/|video\/mp4|application\/(octet-stream|ogg))/i.test(response.headers.get("Content-Type") || "")) {
    const copy = response.clone(), headers = new Headers(copy.headers);
    headers.delete("Content-Range"); headers.set("X-Pipo-Cached-At",String(Date.now()));
    if (range) headers.set("Content-Length",range[3]);
    event.waitUntil((async () => {if(epoch!==audioEpoch) return;await cache.put(key,new Response(copy.body,{status:200,headers}));if(epoch!==audioEpoch) {await cache.delete(key);return;}await trimAudio();})().catch(() => undefined));
  } else if(response.status===206 && range && /^(audio\/|video\/mp4|application\/(octet-stream|ogg))/i.test(response.headers.get("Content-Type") || "")) {
    event.waitUntil(saveAudioFragment(key,response.clone(),range,epoch).catch(() => undefined));
  }
  return response;
}

const assemblyJobs=new Map();
async function saveAudioFragment(key,response,range,epoch) {
  const fragments=await caches.open(FRAGMENT_CACHE),start=Number(range[1]),end=Number(range[2]),total=Number(range[3]);
  if(epoch!==audioEpoch || !Number.isSafeInteger(total) || total<=0 || start<0 || end<start || end>=total) return;
  const fragmentUrl=new URL("/pwa/audio-fragment",self.location.origin);fragmentUrl.searchParams.set("source",key);fragmentUrl.searchParams.set("start",String(start));fragmentUrl.searchParams.set("end",String(end));fragmentUrl.searchParams.set("total",String(total));
  const headers=new Headers(response.headers);headers.delete("Content-Range");headers.set("X-Pipo-Cached-At",String(Date.now()));headers.set("Content-Length",String(end-start+1));
  await fragments.put(fragmentUrl.href,new Response(response.body,{status:200,headers}));
  if(epoch!==audioEpoch) {await fragments.delete(fragmentUrl.href);return;}
  const previous=assemblyJobs.get(key) || Promise.resolve();
  const job=previous.catch(() => undefined).then(async () => {
    if(epoch!==audioEpoch) return;
    const requests=await fragments.keys(),rows=requests.flatMap(request => {const url=new URL(request.url);return url.searchParams.get("source")===key && Number(url.searchParams.get("total"))===total?[{request,start:Number(url.searchParams.get("start")),end:Number(url.searchParams.get("end"))}]:[]}).sort((a,b) => a.start-b.start || b.end-a.end);
    let cursor=0;const segments=[];
    for(const row of rows) {if(row.start>cursor) break;if(row.end<cursor) continue;segments.push({...row,skip:cursor-row.start,length:row.end-cursor+1});cursor=row.end+1;if(cursor>=total) break;}
    if(cursor>=total) {
      let index=0,reader,skip=0,remaining=0;
      const stream=new ReadableStream({async pull(controller) {
        try {while(true) {
          if(!reader) {const segment=segments[index++];if(!segment) {controller.close();return;}const part=await fragments.match(segment.request);if(!part?.body) throw new Error("音频片段已失效");reader=part.body.getReader();skip=segment.skip;remaining=segment.length;}
          const chunk=await reader.read();if(chunk.done) {reader=undefined;if(remaining) throw new Error("音频片段不完整");continue;}
          const ignored=Math.min(skip,chunk.value.length);skip-=ignored;const bytes=chunk.value.subarray(ignored,ignored+remaining);remaining-=bytes.length;
          if(remaining===0) {await reader.cancel();reader=undefined;}
          if(bytes.length) {controller.enqueue(bytes);return;}
        }}catch(error) {controller.error(error);}
      },cancel() {return reader?.cancel();}});
      const completeHeaders=new Headers(headers);completeHeaders.set("Content-Length",String(total));completeHeaders.set("Accept-Ranges","bytes");
      const audio=await caches.open(AUDIO_CACHE);if(epoch!==audioEpoch) return;
      await audio.put(key,new Response(stream,{status:200,headers:completeHeaders}));
      if(epoch!==audioEpoch) {await audio.delete(key);return;}
      await Promise.all(rows.map(row => fragments.delete(row.request)));await trimAudio();
    }
    // Partial downloads are temporary and do not count as playable offline songs.
    await trimFragments(fragments);
  });assemblyJobs.set(key,job);try {await job;}finally {if(assemblyJobs.get(key)===job) assemblyJobs.delete(key);}
}

async function trimFragments(cache) {
  const rows=(await Promise.all((await cache.keys()).map(async request => {const response=await cache.match(request);return response?{request,size:Number(response.headers.get("Content-Length"))||0,at:Number(response.headers.get("X-Pipo-Cached-At"))||0}:null;}))).filter(Boolean).sort((a,b)=>a.at-b.at);
  let total=rows.reduce((sum,row)=>sum+row.size,0),count=rows.length;
  for(const row of rows) {if(total<=256*1024*1024&&count<=120&&row.at>Date.now()-86400000) break;await cache.delete(row.request);total-=row.size;count--;}
}

async function audioStats() {
  const cache = await caches.open(AUDIO_CACHE), meta = await caches.open(META_CACHE);
  const saved = await meta.match("/pwa/cache-limit");
  const maxBytes = saved ? (await saved.json()).maxBytes : 4096 * 1024 * 1024;
  const entries = (await Promise.all((await cache.keys()).map(async request => {
    const response = await cache.match(request);
    if(!response) return null;
    const size = Number(response.headers.get("Content-Length")) || (await response.clone().blob()).size;
    return {url:request.url,size,at:Number(response.headers.get("X-Pipo-Cached-At")) || 0};
  }))).filter(Boolean);
  return {totalBytes:entries.reduce((total,entry) => total+entry.size,0),count:entries.length,maxBytes,entries};
}

let trimming;
function trimAudio() {
  return trimming ||= (async () => {
    const stats = await audioStats(), cache = await caches.open(AUDIO_CACHE);
    const quota = await self.navigator.storage?.estimate?.().catch(() => ({}));
    const otherUsage = Math.max(0,(quota?.usage || 0)-stats.totalBytes);
    const limit = Math.min(stats.maxBytes,quota?.quota ? Math.max(0,quota.quota * .85-otherUsage) : stats.maxBytes);
    let total = stats.totalBytes;
    for (const entry of stats.entries.sort((a,b) => a.at-b.at)) { if(total<=limit) break; await cache.delete(entry.url);total-=entry.size; }
  })().finally(() => {trimming=undefined;});
}

self.addEventListener("message", (event) => {
  const data = event.data;
  if (data && ["AUDIO_STATS","AUDIO_CLEAR","AUDIO_LIMIT"].includes(data.type)) {
    event.waitUntil((async () => {
      try {
        if (data.type === "AUDIO_CLEAR") {audioEpoch++;await Promise.all([caches.delete(AUDIO_CACHE),caches.delete(FRAGMENT_CACHE)]);}
        if (data.type === "AUDIO_LIMIT") {const cache=await caches.open(META_CACHE);await cache.put("/pwa/cache-limit",new Response(JSON.stringify({maxBytes:Math.max(64,Math.min(4096,Number(data.mb)||4096))*1024*1024})));await trimAudio();}
        const {entries,...stats}=await audioStats(); event.ports[0]?.postMessage({result:stats});
      } catch { event.ports[0]?.postMessage({error:"音频缓存暂时不可用"}); }
    })()); return;
  }
  if (!data || data.type !== "CACHE_URLS" || !Array.isArray(data.urls)) return;

  const urls = allowedMessageUrls(data.urls);

  event.waitUntil(
    cacheUrls(urls).then(
      () => reply(event.source, { type: "CACHE_READY", urls }),
      (error) =>
        reply(event.source, {
          type: "CACHE_ERROR",
          message: error instanceof Error ? error.message : "资源缓存失败。",
        }),
    ),
  );
});
