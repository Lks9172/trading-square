// OpenTelemetry 는 반드시 다른 모듈 require 이전에 초기화돼야 auto-instrumentation
// 이 axios/http/express 를 훅할 수 있다. 최상단 import 유지.
import './telemetry';

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
import { checkAndNotify, sendStartupSnapshot, sendWeeklyReportText } from './services/telegram';
import { buildWeeklyReport, detectRuleViolations, formatWeeklyReportText } from './services/weekly-report';
import { startTelegramCommandPoller } from './services/telegram-commands';
import { logger, serializeError } from './services/logger';

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
    logger.info('starting initial snapshot');
    const snapshot = await getSnapshot(DEFAULT_PROFILE, true);
    logger.info('initial snapshot ready, sending startup telegram');
    const ok = await sendStartupSnapshot(snapshot.signals, snapshot.regime, snapshot.allocation);
    logger.info({ ok }, 'startup telegram result');
  } catch (error) {
    logger.error({ error: serializeError(error) }, 'initial refresh failed');
  }
}, 60000);

void ensureBackfill(process.env.FRED_API_KEY || '').catch((error) => {
  logger.error({ error: serializeError(error) }, 'initial backfill failed');
});

void refreshComputedHistories().catch((error) => {
  logger.error({ error: serializeError(error) }, 'initial computed history refresh failed');
});

// 21차 P1#6: cron 차등 — KR 장중 3분 / 미국 장중 5분 / 야간·장후 15분 / 주말 1시간.
// market-hours 는 utils/market-hours.ts 의 isKoreaMarketOpen / isUSMarketOpen 활용.
async function runScheduledRefresh(triggeredBy: string): Promise<void> {
  try {
    logger.info({ triggeredBy }, 'scheduled snapshot refresh started');
    const snapshot = await getSnapshot(DEFAULT_PROFILE, true);
    await checkAndNotify(snapshot.signals, snapshot.regime, snapshot.allocation);
    logger.info({
      triggeredBy,
      regime: snapshot.regime.regime,
      regimeScore: snapshot.regime.score,
      signals: snapshot.signals.length,
    }, 'scheduled snapshot refresh completed');
  } catch (error) {
    logger.error({ triggeredBy, error: serializeError(error) }, 'scheduled snapshot refresh failed');
  }
}

// 평일 KR 장중 (09:00–15:30 KST) 3분 — 코스피 상시 업데이트
cron.schedule('*/3 9-14 * * 1-5', () => void runScheduledRefresh('kr-daytime-3min'), { timezone: 'Asia/Seoul' });
cron.schedule('0,3,6,9,12,15,18,21,24,27,30 15 * * 1-5', () => void runScheduledRefresh('kr-close-3min'), { timezone: 'Asia/Seoul' });
// 평일 미국 장중 (22:30 KST – 익일 05:00) 5분
cron.schedule('*/5 22-23 * * 1-5', () => void runScheduledRefresh('us-evening-5min'), { timezone: 'Asia/Seoul' });
cron.schedule('*/5 0-5 * * 2-6', () => void runScheduledRefresh('us-overnight-5min'), { timezone: 'Asia/Seoul' });
// 평일 KR 장후·미국 장전 (06:00–08:59, 16:00–22:29) 15분
cron.schedule('*/15 6-8 * * 1-5', () => void runScheduledRefresh('kr-pre-15min'), { timezone: 'Asia/Seoul' });
cron.schedule('*/15 16-22 * * 1-5', () => void runScheduledRefresh('kr-post-15min'), { timezone: 'Asia/Seoul' });
// 주말 1시간
cron.schedule('0 * * * 0,6', () => void runScheduledRefresh('weekend-1h'), { timezone: 'Asia/Seoul' });

// KST 07:00 히스토리 append — 기존 `0 22 * * *` 는 UTC 22시 = KST 07시였으나
// Asia/Seoul TZ 지정 후엔 `0 7 * * *` 표기가 의미와 동일. 가독성 개선 + TZ 명시.
cron.schedule('0 7 * * *', async () => {
  try {
    const apiKey = process.env.FRED_API_KEY || '';
    await appendDailyData(apiKey);
    await refreshComputedHistories();
    logger.info('daily history append completed');
  } catch (error) {
    logger.error({ error: serializeError(error) }, 'daily history append failed');
  }
}, { timezone: 'Asia/Seoul' });

// 17차 Phase 2 D1: 매주 월요일 KST 08:00 Weekly report Telegram 전송
cron.schedule('0 8 * * 1', async () => {
  try {
    const snapshot = await getSnapshot(DEFAULT_PROFILE, false);
    const report = buildWeeklyReport(snapshot);
    report.ruleViolations = await detectRuleViolations(snapshot);
    const text = formatWeeklyReportText(report);
    const ok = await sendWeeklyReportText(text);
    logger.info({ ok, warnings: report.warnings.length, violations: report.ruleViolations.length }, 'weekly report dispatched');
  } catch (error) {
    logger.error({ error: serializeError(error) }, 'weekly report dispatch failed');
  }
}, { timezone: 'Asia/Seoul' });

// 21차 P2#16: 매일 KST 07:00 사전 경고 push — 임박한 D-day 이벤트 + 변동성 폭주 등.
cron.schedule('0 7 * * *', async () => {
  try {
    const snapshot = await getSnapshot(DEFAULT_PROFILE, false);
    const der = snapshot.derived || {};
    const alerts: string[] = [];
    const ddayKeys: Array<[string, string]> = [
      ['EARNINGS_DDAY_MEGACAP', '메가캡 실적'],
      ['POWELL_SPEECH_DDAY', 'Powell 발언'],
      ['BESSENT_TESTIMONY_DDAY', 'Bessent 증언'],
      ['WARSH_KEYNOTE_DDAY', 'Warsh 강연'],
      ['KDI_FORECAST_DDAY', 'KDI 전망'],
      ['IMF_WEO_DDAY', 'IMF WEO'],
      ['KIF_BIWEEKLY_DDAY', 'KIF 격주'],
      ['BOK_QUARTERLY_OUTLOOK_DDAY', 'BOK 분기'],
      ['BOK_FORECAST_DDAY', 'BOK 정책'],
      ['FOMC_DDAY', 'FOMC'],
      ['CPI_DDAY', 'CPI'],
      ['GEOPOLITICAL_COUNTDOWN_DDAY', '지정학'],
    ];
    for (const [key, label] of ddayKeys) {
      const v = der[key]?.value;
      if (typeof v === 'number' && v >= 0 && v <= 3) {
        alerts.push(`📅 ${label} D-${v}`);
      }
    }
    // 변동성 / 위험 신호
    const conv = der.CONVICTION_SCORE_7AXIS?.value;
    if (typeof conv === 'number' && conv <= -3) alerts.push(`🔴 7축 확신 ${conv}/7 — 약세 정합`);
    const liqRisk = der.LIQUIDITY_RISK_TRANSMISSION?.value;
    if (typeof liqRisk === 'number' && liqRisk >= 2) alerts.push(`🔴 유동성-위험 디커플링 강함`);
    const dgs303w = der.DGS30_3W_CHANGE_BPS?.value;
    if (typeof dgs303w === 'number' && dgs303w >= 30) alerts.push(`🔴 30Y 3주 +${dgs303w}bp — 트러스 패턴 임박`);
    const dca = der.DCA_STAGE_STAGNATION_COUNT?.value;
    if (typeof dca === 'number' && dca >= 1) alerts.push(`🟠 분할매수 정체 ${dca}건`);
    if (alerts.length > 0) {
      const text = `⏰ MacroSquare 사전 경고 (D-3 이내 + 위험 신호)\n${new Date().toLocaleString('ko-KR', { timeZone: 'Asia/Seoul' })}\n\n${alerts.join('\n')}`;
      await sendWeeklyReportText(text);
      logger.info({ alertCount: alerts.length }, 'pre-event alert dispatched');
    }
  } catch (error) {
    logger.error({ error: serializeError(error) }, 'pre-event alert failed');
  }
}, { timezone: 'Asia/Seoul' });

// 17차 Phase 3 D2: Telegram /status /signal /plan /weekly 커맨드 poller 기동.
// 서버 부팅 직후 getUpdates 를 장시간 폴링하지 않도록 초기 startup 이후 지연 시작.
setTimeout(() => startTelegramCommandPoller(30000), 75000);

app.listen(PORT, () => {
  logger.info({ port: PORT }, 'MacroSquare server running');
});
