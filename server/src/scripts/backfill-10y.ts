/**
 * 백테스트용 Yahoo 히스토리를 10년치로 확장.
 *
 * history-store.ts 의 기본 보장(5년)을 건드리지 않고, 스크립트 자체가
 * 직접 Yahoo chart API 를 호출해 10년 기간을 data/history/ 아래 파일로 저장.
 *
 * 실행: tsx src/scripts/backfill-10y.ts
 */

import fs from 'fs/promises';
import path from 'path';
import { fetchYahooHistory } from '../collectors/yahoo';

const DATA_DIR = path.resolve(process.cwd(), 'data', 'history');
const DAYS = Math.round(10 * 365.25); // 10년

const TARGETS: Array<{ key: string; symbol: string }> = [
  { key: 'NASDAQ', symbol: '^IXIC' },
  { key: 'SP500', symbol: '^GSPC' },
  { key: 'KOSPI', symbol: '^KS11' },
  { key: 'GOLD', symbol: 'GC=F' },
  { key: 'SILVER', symbol: 'SI=F' },
  { key: 'COPPER', symbol: 'HG=F' },
  { key: 'WTI', symbol: 'CL=F' },
  { key: 'DXY', symbol: 'DX-Y.NYB' },
  { key: 'USDKRW', symbol: 'KRW=X' },
  { key: 'EWZ', symbol: 'EWZ' },
  { key: 'INDA', symbol: 'INDA' },
  { key: 'VNM', symbol: 'VNM' },
  { key: 'EWJ', symbol: 'EWJ' },
];

async function main() {
  await fs.mkdir(DATA_DIR, { recursive: true });
  for (const { key, symbol } of TARGETS) {
    process.stdout.write(`${key} (${symbol}) ... `);
    try {
      const hist = await fetchYahooHistory(symbol, DAYS);
      if (hist.length < 500) {
        console.log(`WARN only ${hist.length} pts, skipping`);
        continue;
      }
      const points = hist.map((p) => ({ date: p.date, value: p.close }));
      const file = path.join(DATA_DIR, `yahoo-${key.toLowerCase()}.json`);
      await fs.writeFile(file, JSON.stringify(points));
      console.log(`OK ${points.length} pts (${points[0].date} ~ ${points[points.length - 1].date})`);
    } catch (e: any) {
      console.log(`FAIL ${e.message}`);
    }
  }
  console.log('\nDone.');
}

main().catch((e) => { console.error(e); process.exit(1); });
