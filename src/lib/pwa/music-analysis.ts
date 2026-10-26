"use client";

import type { AudioFeatures } from "@/lib/tauri";
import { pwaInvoke } from "./api";
import { repository,type NativeTrack } from "./model";
import { readRecord,writeRecord } from "./storage";
import { nativeFeatures,type NativeAudioFeatures } from "./transition";

export const audioFeatures=new Map<string,NativeAudioFeatures>();
const jobs=new Map<string,Promise<NativeAudioFeatures|null>>();
let lane=Promise.resolve<unknown>(undefined);
export async function cachedFeatures(tracks:NativeTrack[]) {
  await Promise.all(tracks.map(async track => {if(audioFeatures.has(track.id)) return;const cached=await readRecord<AudioFeatures>(`features:${track.id}`).catch(() => undefined);if(cached) audioFeatures.set(track.id,nativeFeatures(cached));}));
}
export function analyzeTrack(track:NativeTrack):Promise<NativeAudioFeatures|null> {
  if(!track.neteaseId) return Promise.resolve(null);if(audioFeatures.has(track.id)) return Promise.resolve(audioFeatures.get(track.id)!);const pending=jobs.get(track.id);if(pending) return pending;
  const job=lane.catch(() => undefined).then(async () => {
    await cachedFeatures([track]);if(audioFeatures.has(track.id)) return audioFeatures.get(track.id)!;
    const url=(await repository.songUrls([track.neteaseId!]))[0]?.url;if(!url) return null;
    const features=nativeFeatures(await pwaInvoke<AudioFeatures>("audio_get_features",{trackId:track.neteaseId,url,cacheBytes:false}));audioFeatures.set(track.id,features);await writeRecord(`features:${track.id}`,features).catch(() => undefined);return features;
  });lane=job;jobs.set(track.id,job);void job.then(() => jobs.delete(track.id),() => jobs.delete(track.id));return job;
}
export function featureSimilarity(left?:AudioFeatures,right?:AudioFeatures) {
  const vector=(features?:AudioFeatures) => !features?.bpm || features.bpmConfidence<.35 ? null : [(features.bpm-60)/120,(features.rmsDb+30)/30,(features.spectralCentroidHz-200)/5800,features.dynamicRangeDb/25].map(value => Math.max(0,Math.min(1,value)));
  const a=vector(left),b=vector(right);if(!a || !b) return 0;return Math.exp(-Math.sqrt(a.reduce((sum,value,index) => sum+(value-b[index])**2,0)/4)*3);
}
