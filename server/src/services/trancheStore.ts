import fs from 'fs/promises';
import path from 'path';

export interface TrancheEntry {
  asset: string;
  stage: number;
  executedAt: string;
  priceAtEntry: number | null;
  regimeAtEntry: string | null;
}

const DATA_DIR = path.resolve(process.cwd(), 'data', 'execution');
const STATE_FILE = path.join(DATA_DIR, 'tranche-state.json');

async function ensureDir() {
  await fs.mkdir(DATA_DIR, { recursive: true });
}

export async function loadTranches(): Promise<TrancheEntry[]> {
  try {
    const raw = await fs.readFile(STATE_FILE, 'utf-8');
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) ? parsed : [];
  } catch (err: any) {
    if (err?.code === 'ENOENT') return [];
    throw err;
  }
}

async function writeAtomic(entries: TrancheEntry[]): Promise<void> {
  await ensureDir();
  const tmp = `${STATE_FILE}.tmp-${process.pid}-${Date.now()}`;
  await fs.writeFile(tmp, JSON.stringify(entries, null, 2));
  await fs.rename(tmp, STATE_FILE);
}

export async function appendTranche(entry: TrancheEntry): Promise<TrancheEntry[]> {
  const existing = await loadTranches();
  existing.push(entry);
  await writeAtomic(existing);
  return existing;
}

export async function listTranches(): Promise<TrancheEntry[]> {
  return loadTranches();
}

export async function clearAssetTranches(asset: string): Promise<TrancheEntry[]> {
  const existing = await loadTranches();
  const remaining = existing.filter((e) => e.asset !== asset);
  await writeAtomic(remaining);
  return remaining;
}

export interface AssetTrancheSummary {
  asset: string;
  executedStages: number[];
  nextStage: number | null;
  latestRegime: string | null;
  latestExecutedAt: string | null;
}

export function summarizeByAsset(entries: TrancheEntry[]): AssetTrancheSummary[] {
  const byAsset = new Map<string, TrancheEntry[]>();
  for (const e of entries) {
    const arr = byAsset.get(e.asset) ?? [];
    arr.push(e);
    byAsset.set(e.asset, arr);
  }
  const out: AssetTrancheSummary[] = [];
  for (const [asset, arr] of byAsset) {
    const sorted = [...arr].sort(
      (a, b) => new Date(a.executedAt).getTime() - new Date(b.executedAt).getTime(),
    );
    const executedStages = Array.from(new Set(sorted.map((s) => s.stage))).sort((a, b) => a - b);
    const maxStage = executedStages[executedStages.length - 1] ?? 0;
    const nextStage = maxStage < 3 ? maxStage + 1 : null;
    const latest = sorted[sorted.length - 1];
    out.push({
      asset,
      executedStages,
      nextStage,
      latestRegime: latest?.regimeAtEntry ?? null,
      latestExecutedAt: latest?.executedAt ?? null,
    });
  }
  return out;
}
