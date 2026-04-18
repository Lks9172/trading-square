import fs from 'fs/promises';
import path from 'path';

const DATA_DIR = path.resolve(process.cwd(), 'data');
const CACHE_DIR = path.join(DATA_DIR, 'source-cache');

export interface SourceCacheEnvelope<T> {
  key: string;
  updatedAt: string;
  value: T;
  meta?: Record<string, unknown>;
}

export interface SourceCacheHit<T> {
  value: T;
  updatedAt: string;
  ageMs: number;
  meta?: Record<string, unknown>;
}

function sanitizeKey(key: string) {
  return key.replace(/[^a-zA-Z0-9_.-]+/g, '_').toLowerCase();
}

function cachePath(key: string) {
  return path.join(CACHE_DIR, `${sanitizeKey(key)}.json`);
}

async function ensureCacheDir() {
  await fs.mkdir(CACHE_DIR, { recursive: true });
}

export async function writeSourceCache<T>(
  key: string,
  value: T,
  meta?: Record<string, unknown>,
): Promise<void> {
  await ensureCacheDir();
  const finalPath = cachePath(key);
  const tmpPath = `${finalPath}.tmp-${process.pid}-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
  const payload: SourceCacheEnvelope<T> = {
    key,
    updatedAt: new Date().toISOString(),
    value,
    ...(meta ? { meta } : {}),
  };
  await fs.writeFile(tmpPath, JSON.stringify(payload));
  await fs.rename(tmpPath, finalPath);
}

export async function readSourceCache<T>(key: string): Promise<SourceCacheEnvelope<T> | null> {
  try {
    const file = await fs.readFile(cachePath(key), 'utf8');
    const parsed = JSON.parse(file) as SourceCacheEnvelope<T>;
    if (!parsed || !parsed.updatedAt || !('value' in parsed)) return null;
    return parsed;
  } catch {
    return null;
  }
}

export async function readSourceCacheWithin<T>(key: string, maxAgeMs: number): Promise<SourceCacheHit<T> | null> {
  const cached = await readSourceCache<T>(key);
  if (!cached) return null;
  const updatedAtMs = new Date(cached.updatedAt).getTime();
  if (!Number.isFinite(updatedAtMs)) return null;
  const ageMs = Date.now() - updatedAtMs;
  if (ageMs > maxAgeMs) return null;
  return {
    value: cached.value,
    updatedAt: cached.updatedAt,
    ageMs,
    meta: cached.meta,
  };
}
