// Small UI preferences stay synchronous. Large libraries/audio use IndexedDB.
export function readPreference<T>(key: string, fallback: T): T {
  if (typeof window === "undefined") return fallback;
  try { const value = localStorage.getItem(`pipo.native.${key}`); return value == null ? fallback : JSON.parse(value) as T; }
  catch { return fallback; }
}
export function writePreference(key: string, value: unknown) {
  try { localStorage.setItem(`pipo.native.${key}`, JSON.stringify(value)); return true; }
  catch { return false; }
}

let database: Promise<IDBDatabase> | undefined;
function open() {
  return database ||= new Promise<IDBDatabase>((resolve, reject) => {
    const request = indexedDB.open("pipo-native-pwa", 1);
    request.onupgradeneeded = () => { request.result.createObjectStore("records"); request.result.createObjectStore("audio"); };
    request.onsuccess = () => { request.result.onversionchange = () => { request.result.close(); database = undefined; }; resolve(request.result); };
    request.onerror = () => { database = undefined; reject(request.error); };
    request.onblocked = () => { database = undefined; reject(new Error("请关闭其它 PIPO 页面后重试。")); };
  });
}
export async function readRecord<T>(key: string, store: "records" | "audio" = "records"): Promise<T | undefined> {
  const db = await open();
  return new Promise((resolve, reject) => { const request = db.transaction(store).objectStore(store).get(key); request.onsuccess = () => resolve(request.result as T | undefined); request.onerror = () => reject(request.error); });
}
export async function writeRecord(key: string, value: unknown, store: "records" | "audio" = "records") {
  const db = await open();
  return new Promise<void>((resolve, reject) => { const tx = db.transaction(store, "readwrite"); tx.objectStore(store).put(value, key); tx.oncomplete = () => resolve(); tx.onerror = () => reject(tx.error); tx.onabort = () => reject(tx.error); });
}
export async function deleteRecord(key: string, store: "records" | "audio" = "records") {
  const db = await open();
  return new Promise<void>((resolve, reject) => { const tx = db.transaction(store, "readwrite"); tx.objectStore(store).delete(key); tx.oncomplete = () => resolve(); tx.onerror = () => reject(tx.error); });
}
export const messageOf = (error: unknown) => error instanceof Error ? error.message : "操作失败，请重试。";
