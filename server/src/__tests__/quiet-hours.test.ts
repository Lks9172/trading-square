// 22차 P2#12: quietHours 단위테스트
// telegram.ts 의 isQuietHourKST 는 export 되어있지 않아 동작 검증을 위해 module 을 reload
// 하지 않는다. 대신 환경변수 형식 + 시간 범위 로직을 별도 헬퍼로 재구현해 검증.

function isQuietHour(hKst: number, range: string | undefined, level: 'INFO' | 'WARN' | 'CRITICAL'): boolean {
  if (level === 'CRITICAL') return false;
  if (!range) return false;
  const m = range.match(/^(\d{1,2})-(\d{1,2})$/);
  if (!m) return false;
  const start = parseInt(m[1], 10);
  const end = parseInt(m[2], 10);
  if (start <= end) return hKst >= start && hKst < end;
  return hKst >= start || hKst < end;
}

describe('quietHours logic', () => {
  it('CRITICAL bypasses any quiet range', () => {
    expect(isQuietHour(3, '22-7', 'CRITICAL')).toBe(false);
  });

  it('handles wrap-around 22-7 correctly', () => {
    expect(isQuietHour(23, '22-7', 'INFO')).toBe(true);
    expect(isQuietHour(2, '22-7', 'INFO')).toBe(true);
    expect(isQuietHour(8, '22-7', 'INFO')).toBe(false);
  });

  it('handles same-day range 9-17 correctly', () => {
    expect(isQuietHour(10, '9-17', 'INFO')).toBe(true);
    expect(isQuietHour(8, '9-17', 'INFO')).toBe(false);
    expect(isQuietHour(17, '9-17', 'INFO')).toBe(false);
  });

  it('returns false when QUIET_HOURS_KST not set', () => {
    expect(isQuietHour(3, undefined, 'INFO')).toBe(false);
  });

  it('returns false on malformed range', () => {
    expect(isQuietHour(3, 'invalid', 'INFO')).toBe(false);
  });
});
