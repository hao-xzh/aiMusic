export type PwaTrack = {
  id: string;
  name: string;
  size: number;
  mime: string;
  addedAt: number;
  blob: Blob;
  lrc?: string;
};

export type PwaSession = {
  trackId: string | null;
  positionSec: number;
};

type StoredSession = PwaSession & { key: "current" };

const DATABASE_NAME = "pipo-pwa-library";
const DATABASE_VERSION = 1;
const TRACKS_STORE = "tracks";
const SESSION_STORE = "session";

let databasePromise: Promise<IDBDatabase> | null = null;

function storageError(action: string, cause?: unknown): Error {
  const detail = cause instanceof DOMException && cause.message ? `：${cause.message}` : "";
  return new Error(`${action}失败，请检查浏览器的本地存储权限${detail}`);
}

function openDatabase(): Promise<IDBDatabase> {
  if (databasePromise) return databasePromise;
  if (typeof indexedDB === "undefined") {
    return Promise.reject(new Error("当前浏览器不支持本地音乐存储。"));
  }

  const opening = new Promise<IDBDatabase>((resolve, reject) => {
    let request: IDBOpenDBRequest;
    try {
      request = indexedDB.open(DATABASE_NAME, DATABASE_VERSION);
    } catch (error) {
      reject(storageError("打开本地音乐库", error));
      return;
    }

    request.onupgradeneeded = () => {
      const database = request.result;
      if (!database.objectStoreNames.contains(TRACKS_STORE)) {
        database.createObjectStore(TRACKS_STORE, { keyPath: "id" });
      }
      if (!database.objectStoreNames.contains(SESSION_STORE)) {
        database.createObjectStore(SESSION_STORE, { keyPath: "key" });
      }
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(storageError("打开本地音乐库", request.error));
    request.onblocked = () => reject(new Error("本地音乐库正被其它页面占用，请关闭其它 Pipo 页面后重试。"));
  });

  databasePromise = opening.catch((error) => {
    databasePromise = null;
    throw error;
  });
  return databasePromise;
}

function requestResult<T>(request: IDBRequest<T>, action: string): Promise<T> {
  return new Promise((resolve, reject) => {
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(storageError(action, request.error));
  });
}

function waitForTransaction(transaction: IDBTransaction, action: string): Promise<void> {
  return new Promise((resolve, reject) => {
    transaction.oncomplete = () => resolve();
    transaction.onerror = () => reject(storageError(action, transaction.error));
    transaction.onabort = () => reject(storageError(action, transaction.error));
  });
}

function abortTransaction(transaction: IDBTransaction) {
  try {
    transaction.abort();
  } catch {
    // 事务已经结束，无需再处理。
  }
}

export async function readLibrary(): Promise<PwaTrack[]> {
  const database = await openDatabase();
  const transaction = database.transaction(TRACKS_STORE, "readonly");
  const tracks = await requestResult(
    transaction.objectStore(TRACKS_STORE).getAll() as IDBRequest<PwaTrack[]>,
    "读取本地音乐库",
  );
  await waitForTransaction(transaction, "读取本地音乐库");
  return tracks.sort((a, b) => a.addedAt - b.addedAt);
}

export async function putTracks(tracks: PwaTrack[]): Promise<void> {
  const database = await openDatabase();
  const transaction = database.transaction(TRACKS_STORE, "readwrite");
  try {
    const store = transaction.objectStore(TRACKS_STORE);
    for (const track of tracks) store.put(track);
    await waitForTransaction(transaction, "保存本地音乐");
  } catch (error) {
    abortTransaction(transaction);
    throw error instanceof Error ? error : storageError("保存本地音乐", error);
  }
}

export async function removeTrack(id: string): Promise<void> {
  const database = await openDatabase();
  const transaction = database.transaction(TRACKS_STORE, "readwrite");
  try {
    transaction.objectStore(TRACKS_STORE).delete(id);
    await waitForTransaction(transaction, "删除本地音乐");
  } catch (error) {
    abortTransaction(transaction);
    throw error instanceof Error ? error : storageError("删除本地音乐", error);
  }
}

export async function saveLyrics(id: string, lrc: string): Promise<void> {
  const database = await openDatabase();
  const transaction = database.transaction(TRACKS_STORE, "readwrite");
  try {
    const store = transaction.objectStore(TRACKS_STORE);
    const track = await requestResult(
      store.get(id) as IDBRequest<PwaTrack | undefined>,
      "读取本地音乐",
    );
    if (!track) {
      abortTransaction(transaction);
      throw new Error("未找到该音乐，无法保存歌词。");
    }
    store.put({ ...track, lrc });
    await waitForTransaction(transaction, "保存歌词");
  } catch (error) {
    abortTransaction(transaction);
    throw error instanceof Error ? error : storageError("保存歌词", error);
  }
}

export async function readSession(): Promise<PwaSession | null> {
  const database = await openDatabase();
  const transaction = database.transaction(SESSION_STORE, "readonly");
  const stored = await requestResult(
    transaction.objectStore(SESSION_STORE).get("current") as IDBRequest<StoredSession | undefined>,
    "读取播放进度",
  );
  await waitForTransaction(transaction, "读取播放进度");
  if (!stored) return null;
  return { trackId: stored.trackId, positionSec: stored.positionSec };
}

export async function saveSession(session: PwaSession): Promise<void> {
  const database = await openDatabase();
  const transaction = database.transaction(SESSION_STORE, "readwrite");
  try {
    transaction.objectStore(SESSION_STORE).put({ key: "current", ...session } satisfies StoredSession);
    await waitForTransaction(transaction, "保存播放进度");
  } catch (error) {
    abortTransaction(transaction);
    throw error instanceof Error ? error : storageError("保存播放进度", error);
  }
}
