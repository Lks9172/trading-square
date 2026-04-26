export function isUSMarketOpen(): boolean {
  const now = new Date();
  const etHour = parseInt(
    now.toLocaleString('en-US', { timeZone: 'America/New_York', hour: 'numeric', hour12: false })
  );
  const etDay = parseInt(
    now.toLocaleString('en-US', { timeZone: 'America/New_York', weekday: 'narrow' })
  ) || now.getUTCDay();

  const dayNY = new Date(now.toLocaleString('en-US', { timeZone: 'America/New_York' })).getDay();
  if (dayNY === 0 || dayNY === 6) return false;
  return etHour >= 9 && etHour < 16;
}

export function isKRMarketOpen(): boolean {
  const now = new Date();
  const krHour = parseInt(
    now.toLocaleString('en-US', { timeZone: 'Asia/Seoul', hour: 'numeric', hour12: false })
  );
  const dayKR = new Date(now.toLocaleString('en-US', { timeZone: 'Asia/Seoul' })).getDay();
  if (dayKR === 0 || dayKR === 6) return false;
  return krHour >= 9 && krHour < 16;
}

export type PriceSource = 'spot' | 'futures';

export function getUSPriceSource(): PriceSource {
  return isUSMarketOpen() ? 'spot' : 'futures';
}
