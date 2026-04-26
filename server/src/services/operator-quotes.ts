/**
 * 운영자 §전하는 말 9단락 회전 시스템 (22차 P1#4).
 *
 * 노션 §전하는 말 원문 9 단락을 상수화. 매일 KST 자정 기준 (Math.floor(epochDays / 1) % 9)
 * 로 회전하여 OPERATOR_PHILOSOPHY_QUOTE_INDEX derived 노출.
 * weekly-report / Dashboard footer / RegimeHeader 에서 동일 회전 인용 사용.
 */

export const OPERATOR_QUOTES_KO: ReadonlyArray<{ short: string; full: string }> = [
  {
    short: '투자는 바둑 — 큰 흐름 먼저',
    full: '투자는 바둑처럼 큰 흐름을 먼저 봐야 합니다. 복기하고 공부하면서 실력을 쌓아야 합니다.',
  },
  {
    short: '감정은 배제하고 냉정하게',
    full: '감정은 최대한 배제하고 냉정하게 — 시스템이 있으면 심리 싸움에서 이길 수 있습니다.',
  },
  {
    short: '성급함보다 기다림이 무기',
    full: '성급하게 움직이기보다는 기회를 기다릴 줄 알아야 합니다. 확실하지 않으면 판단하지 마세요.',
  },
  {
    short: '강한 리더 추종은 손실만 남는다',
    full: '강해 보이는 리더를 따라가는 것은 안도감을 주지만 결국 손실만 남습니다 — 자기 기준이 있어야 합니다.',
  },
  {
    short: '나는 러닝 메이트가 되고 싶다',
    full: '나는 리더가 아닌 러닝 메이트가 되고 싶습니다. 함께 뛰며 더 멀리 갑니다.',
  },
  {
    short: '내 손가락이 부러질 때까지 공유',
    full: '내 손가락이 부러질 때까지 자료 공유는 계속될 것입니다. 우리가 함께 성장하기 위함입니다.',
  },
  {
    short: '내가 사라지는 경우의 수는 단 하나, 손',
    full: '내가 사라지는 경우의 수는 단 하나, 손입니다. 그날까지 함께 합니다.',
  },
  {
    short: '끌려다니면 절대 부자가 될 수 없다',
    full: '끌려다니는 사람은 절대 부자가 될 수 없습니다. 시스템과 자기 기준으로 끌어가세요.',
  },
  {
    short: '우린 함께 웃자',
    full: '우린 함께 웃자. — 자산제곱',
  },
];

/** 매일 0시(UTC) 기준 회전 인덱스 — 0..8 */
export function getDailyQuoteIndex(at: Date = new Date()): number {
  const epochDays = Math.floor(at.getTime() / 86400000);
  return ((epochDays % OPERATOR_QUOTES_KO.length) + OPERATOR_QUOTES_KO.length) % OPERATOR_QUOTES_KO.length;
}

export function getDailyQuote(at: Date = new Date()): { short: string; full: string; index: number } {
  const idx = getDailyQuoteIndex(at);
  return { ...OPERATOR_QUOTES_KO[idx], index: idx };
}
