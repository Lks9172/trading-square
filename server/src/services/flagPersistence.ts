/**
 * Fix #FE1: Regime/Flag 히스테리시스.
 *
 * 문제: OVERHEATED / FISCAL_STRESS / BOND_VIGILANTE_WARNING / STAGFLATION_WARNING /
 *      CREDIT_STRESS_FLAG / NASDAQ_CHASE_WARNING / KOSPI_CHASE_WARNING 같은 이진 플래그가
 *      5분 스냅샷 단위로 1↔0 flicker 하면 BASE_ALLOCATIONS 가 즉시 뒤집혀 nasdaq 38→15→38
 *      처럼 20%p 요동. 영상 원칙 "추격 금지 / 분할매수 필수" 위반.
 *
 * 구현: 각 플래그별 {value, sinceDate, lastChecked} 를 디스크에 영속.
 *       raw 값이 현재 확정된 persisted value 와 달라도, 변경 후 minDays 가 경과해야
 *       실제로 발동 값이 뒤집힌다. 그 전에는 persisted value 를 그대로 유지.
 *
 * 적용 경로: state/cache.ts snapshot 빌드 경로에만 적용.
 *           history 재계산(recomputeFullDerivedForDate)은 이미 날짜별 확정값이라 히스테리시스 불필요.
 */

import fs from 'fs/promises';
import path from 'path';

const DATA_DIR = path.resolve(process.cwd(), 'data', 'runtime');
const STATE_FILE = path.join(DATA_DIR, 'flag-state.json');

export interface FlagEntry {
  /** 현재 확정 값 (0|1) — 소비자가 읽는 값. */
  value: 0 | 1;
  /** raw 가 value 와 다르게 관찰되기 시작한 날짜 (ISO yyyy-mm-dd). raw==value 면 value 의 확정 시점. */
  sinceDate: string;
  /** 마지막 갱신 시각 (ISO timestamp). */
  lastChecked: string;
  /** 마지막으로 본 raw 값 (관측성 용도). */
  lastRaw: 0 | 1;
}

export type FlagState = Record<string, FlagEntry>;

let cached: FlagState | null = null;

async function ensureDir(): Promise<void> {
  await fs.mkdir(DATA_DIR, { recursive: true });
}

async function loadState(): Promise<FlagState> {
  if (cached) return cached;
  try {
    const raw = await fs.readFile(STATE_FILE, 'utf-8');
    const parsed = JSON.parse(raw);
    cached = (parsed && typeof parsed === 'object') ? parsed as FlagState : {};
  } catch (err: any) {
    if (err?.code === 'ENOENT') cached = {};
    else throw err;
  }
  return cached!;
}

async function saveState(state: FlagState): Promise<void> {
  await ensureDir();
  const tmp = `${STATE_FILE}.tmp-${process.pid}-${Date.now()}`;
  await fs.writeFile(tmp, JSON.stringify(state, null, 2));
  await fs.rename(tmp, STATE_FILE);
  cached = state;
}

function todayISO(): string {
  return new Date().toISOString().split('T')[0];
}

function nowISO(): string {
  return new Date().toISOString();
}

function daysBetween(fromISO: string, toISO: string): number {
  const a = new Date(`${fromISO}T00:00:00Z`).getTime();
  const b = new Date(`${toISO}T00:00:00Z`).getTime();
  return Math.floor((b - a) / 86400000);
}

/**
 * 히스테리시스 적용된 플래그 값 반환.
 *
 * 규칙:
 *  - 저장된 엔트리가 없으면 raw 를 그대로 확정(초기화).
 *  - raw == stored.value: sinceDate 는 유지, lastChecked/lastRaw 갱신.
 *  - raw != stored.value: lastRaw 가 raw 와 다르면 sinceDate 리셋(반대 방향으로 꺾임).
 *      그 외에는 sinceDate 유지. sinceDate 로부터 minDays 이상 경과하면 flip 확정.
 *      아니면 stored.value 유지.
 *
 * @param key 플래그 키 (예: 'OVERHEATED').
 * @param rawValue 이번 스냅샷의 원값 (0 또는 1).
 * @param minDays 반전까지 요구되는 최소 일수 (기본 7).
 * @returns 확정된 0|1 값 (derived 에 덮어써야 할 값).
 */
export async function hardenFlag(
  key: string,
  rawValue: number,
  minDays: number = 7,
): Promise<0 | 1> {
  const raw: 0 | 1 = rawValue === 1 ? 1 : 0;
  const state = await loadState();
  const today = todayISO();
  const now = nowISO();
  const existing = state[key];

  if (!existing) {
    // 초기화 — 첫 관측값을 바로 확정. "관측 없음" 상태에서 flicker 방지 목적이 없으므로.
    state[key] = { value: raw, sinceDate: today, lastChecked: now, lastRaw: raw };
    await saveState(state);
    return raw;
  }

  // raw 가 현재 확정 value 와 같으면: sinceDate 는 value 확정 시점이므로 유지, 관측 메타만 갱신.
  if (raw === existing.value) {
    existing.lastChecked = now;
    existing.lastRaw = raw;
    await saveState(state);
    return existing.value;
  }

  // raw 가 확정 value 와 다르다 → 반전 카운트다운 중.
  //   직전 lastRaw 와도 다르면 꺾임 발생 → sinceDate 리셋 (반전 지속 아님).
  //   같으면 sinceDate 유지하고 경과일 계산.
  if (existing.lastRaw !== raw) {
    existing.sinceDate = today;
  }
  existing.lastRaw = raw;
  existing.lastChecked = now;

  const elapsed = daysBetween(existing.sinceDate, today);
  if (elapsed >= minDays) {
    existing.value = raw;
    existing.sinceDate = today; // 확정 시점 리셋
  }

  await saveState(state);
  return existing.value;
}

/** 테스트/수동 리셋용. */
export async function _resetFlagStateForTest(): Promise<void> {
  cached = {};
  try {
    await fs.unlink(STATE_FILE);
  } catch {
    /* noop */
  }
}

/** 관측용 — 현재 상태 스냅샷 반환 (복사본). */
export async function readFlagState(): Promise<FlagState> {
  const state = await loadState();
  return JSON.parse(JSON.stringify(state));
}
