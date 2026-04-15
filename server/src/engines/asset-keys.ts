/**
 * 자산 식별자 ↔ allocation 키 공통 매핑.
 *
 * 배경: allocation.ts 의 SIGNAL_ASSET_MAP 과 execution_plan.ts 의 ASSET_ALLOC_KEY 가
 * 각각 독립 선언되어 있어 (a) LEVERAGE 의 포함 여부가 갈렸고 (b) 새 자산 추가 시
 * 양쪽을 동시에 고쳐야 하는 실수 유발원이 되었다. Fix #8(2차 감사) — 단일 상수로 통일.
 *
 * LEVERAGE 주의:
 *  - allocation 단계의 **signal multiplier 루프** 는 LEVERAGE 에 대해 이 맵을 무시한다
 *    (base.leverage 는 leverageAllowed 게이트로만 결정됨). 맵 자체에는 포함시키되,
 *    signal 배수 적용 분기에서 `sig.asset !== 'LEVERAGE'` 가드로 배제한다.
 *  - execution_plan 에서는 LEVERAGE 도 동일하게 allocationPct 참조 대상이라 포함 필요.
 */
export const ASSET_TO_ALLOC_KEY: Record<string, string> = {
  NASDAQ: 'nasdaq',
  KOSPI: 'korea',
  GOLD: 'gold',
  SILVER: 'silver',
  COPPER: 'copper',
  CASH: 'cash',
  LEVERAGE: 'leverage',
  EMERGING: 'emerging',
};

export const SUPPORTED_ASSETS = Object.keys(ASSET_TO_ALLOC_KEY);
