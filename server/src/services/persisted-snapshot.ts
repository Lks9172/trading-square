import { SystemSnapshot } from '../types/indicators';
import { readSourceCache, writeSourceCache } from './source-cache';

const LATEST_SYSTEM_SNAPSHOT_CACHE_KEY = 'latest-system-snapshot-default-v1';

export async function writeLatestPersistedSnapshot(snapshot: SystemSnapshot): Promise<void> {
  await writeSourceCache(LATEST_SYSTEM_SNAPSHOT_CACHE_KEY, snapshot, {
    timestamp: snapshot.timestamp,
    regime: snapshot.regime?.regime,
    regimeScore: snapshot.regime?.score,
  });
}

export async function readLatestPersistedSnapshot(): Promise<SystemSnapshot | null> {
  const cached = await readSourceCache<SystemSnapshot>(LATEST_SYSTEM_SNAPSHOT_CACHE_KEY);
  return cached?.value ?? null;
}
