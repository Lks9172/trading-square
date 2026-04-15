import dns from 'dns';
// Node 17+ 기본 DNS 결과 순서가 verbatim(IPv6 우선)이라 Docker 브릿지에서
// IPv6 라우팅 막힌 환경에선 telegram API 등 outbound 호출이 ETIMEDOUT 됨.
// NODE_OPTIONS=--dns-result-order=ipv4first 는 Node 런타임 옵션 정책상 간헐적으로
// 무시되는 케이스가 있어 코드 레벨로 강제 보장.
dns.setDefaultResultOrder('ipv4first');

import express from 'express';
import cors from 'cors';
import dotenv from 'dotenv';
import cron from 'node-cron';
import apiRouter from './routes/api';
import backtestRouter from './routes/backtest';
import { DEFAULT_PROFILE, getSnapshot } from './state/cache';
import { ensureBackfill, refreshComputedHistories, appendDailyData } from './state/history-store';
import { checkAndNotify, sendStartupSnapshot } from './services/telegram';

dotenv.config();

const app = express();
const PORT = parseInt(process.env.PORT || '5846', 10);

app.use(cors());
app.use(express.json());
app.use('/api', apiRouter);
app.use('/api/backtest', backtestRouter);

// 서버 시작 후 Docker 네트워크·DNS 캐시가 안정될 시간 확보 (60s).
// 그 후에 snapshot → startup telegram. telegram 실패는 내부 5회 재시도로 복구 시도.
setTimeout(async () => {
  try {
    console.log('Starting initial snapshot...');
    const snapshot = await getSnapshot(DEFAULT_PROFILE, true);
    console.log('Snapshot ready, sending startup telegram...');
    const ok = await sendStartupSnapshot(snapshot.signals, snapshot.regime, snapshot.allocation);
    console.log('Startup telegram result:', ok);
  } catch (error) {
    console.error('Initial refresh failed:', error);
  }
}, 60000);

void ensureBackfill(process.env.FRED_API_KEY || '').catch((error) => {
  console.error('Initial backfill failed:', error);
});

void refreshComputedHistories().catch((error) => {
  console.error('Initial computed history refresh failed:', error);
});

// Fix #5(2차 감사): 모든 cron 에 timezone 명시. 5분 스냅샷은 TZ 영향 없지만 일관성 위해 동일 옵션 부여.
cron.schedule('*/5 * * * *', async () => {
  try {
    const snapshot = await getSnapshot(DEFAULT_PROFILE, true);
    await checkAndNotify(snapshot.signals, snapshot.regime, snapshot.allocation);
  } catch (error) {
    console.error('Scheduled snapshot refresh failed:', error);
  }
}, { timezone: 'Asia/Seoul' });

// KST 07:00 히스토리 append — 기존 `0 22 * * *` 는 UTC 22시 = KST 07시였으나
// Asia/Seoul TZ 지정 후엔 `0 7 * * *` 표기가 의미와 동일. 가독성 개선 + TZ 명시.
cron.schedule('0 7 * * *', async () => {
  try {
    const apiKey = process.env.FRED_API_KEY || '';
    await appendDailyData(apiKey);
    await refreshComputedHistories();
    console.log('Daily history append completed');
  } catch (error) {
    console.error('Daily history append failed:', error);
  }
}, { timezone: 'Asia/Seoul' });

app.listen(PORT, () => {
  console.log(`MacroSquare server running on port ${PORT}`);
});
