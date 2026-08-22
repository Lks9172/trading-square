'use client';

/**
 * 22차 P1#5: 첫 방문 onboarding 4스텝 안내 카드.
 * localStorage 'onboarding_done_v1' 미설정 시 노출. 우상단 ? 버튼으로 재호출 가능.
 * 페이지 전체를 덮지 않아 안내 중에도 탐색/실행 UI를 계속 사용할 수 있다.
 */

import { useEffect, useState } from 'react';

// 28차 영상6 §"종목 < 타이밍 < 비중 < 심리" 순서로 재정렬
const STEPS = [
  {
    title: '⏱️ 1단계 — 시간 프레임 정의',
    body: '/plan 페이지에서 horizon 을 선택하세요. 단기 (며칠) / 스윙 (며칠~몇주) / 장기 (수개월~수년). video6 핵심: "시간 프레임이 정해지지 않으면 전략이 없는 겁니다." 같은 하락도 시간 프레임에 따라 의미가 다릅니다.',
    cite: 'video6 §"가장 먼저 해야 하는 게 얼마나 오래 들고 있을까"',
  },
  {
    title: '💰 2단계 — 비중 정의 (총알 보존)',
    body: '/plan 에서 totalCapitalKRW + currentHoldings 8자산 비중 입력. video6: "총알을 처음에 다 써 버리면 진짜 기회가 왔을 때 쏠 게 없다." 분할매수로 평균 단가를 낮추고 반등 시 수익.',
    cite: 'video6 §"마트 삼겹살 반값이면 더 산다 vs 주식 반값에는 판다"',
  },
  {
    title: '🎬 3단계 — 시나리오 작성 (심리 이김)',
    body: '시스템이 SCENARIO_GATE_A_B 로 시나리오 A (협상 타결 → 외인 복귀) / B (협상 결렬 → 현금 비중) 자동 분기. video6: "시나리오가 있다면 탐욕도 공포도 군중심리도 이길 수 있습니다."',
    cite: 'video6 §"시나리오 = 심리를 이기는 유일한 방법"',
  },
  {
    title: '📊 4단계 — 시스템 신호 활용',
    body: '레짐 신호등 + 7축 조건 합치 + Telegram /status /log 5종 명령. 합치도는 확률이 아니므로 가격·수급·손실 한도와 함께 확인합니다. ConvictionPanel 핵심 카드는 collapsible 6영역으로 모바일 최적화. /log 는 즉시 trade-log + horizon 정합 체크.',
    cite: 'video6 §"오늘 내용만 알면 상위 10%"',
  },
];

export function Onboarding() {
  const [step, setStep] = useState(0);
  const [open, setOpen] = useState(false);

  useEffect(() => {
    if (typeof window === 'undefined') return;
    const done = window.localStorage.getItem('onboarding_done_v1');
    if (done) return;
    const timer = window.setTimeout(() => setOpen(true), 0);
    return () => window.clearTimeout(timer);
  }, []);

  function close() {
    setOpen(false);
    if (typeof window !== 'undefined') {
      window.localStorage.setItem('onboarding_done_v1', '1');
    }
  }

  function next() {
    if (step < STEPS.length - 1) setStep(step + 1);
    else close();
  }

  if (!open) {
    return (
      <button
        onClick={() => { setStep(0); setOpen(true); }}
        className="fixed top-12 right-4 z-30 w-7 h-7 rounded-full border border-slate-700 bg-slate-900/80 text-slate-300 hover:text-cyan-300 hover:border-cyan-600 text-xs"
        title="Onboarding 다시 보기"
      >?</button>
    );
  }

  const current = STEPS[step];

  return (
    <aside
      aria-label="처음 사용 안내"
      className="fixed bottom-4 left-4 right-4 z-30 ml-auto max-w-md rounded-xl border border-cyan-700 bg-slate-950/95 p-5 shadow-2xl backdrop-blur sm:left-auto"
    >
        <div className="flex items-start justify-between gap-3">
          <div className="text-xs text-slate-400 mb-2">단계 {step + 1} / {STEPS.length}</div>
          <button
            type="button"
            onClick={close}
            className="-mr-1 -mt-1 inline-flex min-h-8 min-w-8 touch-manipulation items-center justify-center rounded-full text-slate-400 hover:bg-white/5 hover:text-white"
            aria-label="처음 사용 안내 닫기"
          >
            ×
          </button>
        </div>
        <h2 className="text-lg font-bold text-slate-100 mb-2">{current.title}</h2>
        <p className="text-sm text-slate-200 leading-relaxed">{current.body}</p>
        <p className="text-[10px] text-slate-500 mt-2 italic">{current.cite}</p>
        <div className="mt-4 flex justify-between gap-2">
          <button type="button" onClick={close} className="min-h-10 touch-manipulation px-2 text-xs text-slate-400 hover:text-slate-200">건너뛰기</button>
          <button type="button" onClick={next} className="min-h-10 touch-manipulation rounded bg-cyan-600 px-3 py-1.5 text-sm text-white hover:bg-cyan-500">
            {step < STEPS.length - 1 ? '다음 →' : '시작하기'}
          </button>
        </div>
    </aside>
  );
}
