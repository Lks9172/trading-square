'use client';

/**
 * 22차 P1#5: 첫 방문 onboarding 4스텝 모달.
 * localStorage 'onboarding_done_v1' 미설정 시 노출. 우상단 ? 버튼으로 재호출 가능.
 */

import { useEffect, useState } from 'react';

const STEPS = [
  {
    title: '🎯 레짐 신호등',
    body: '상단의 RegimeHeader 가 현재 시장 국면을 6색 신호등으로 보여줍니다 (RISK_ON 🟢 / NEUTRAL 🔵 / CAUTION 🟡 / CORRECTION 🟠 / PANIC 🔴 / RECESSION ⚫). 점수 0-100 + 핵심 컴포넌트 변화도 함께.',
    cite: 'video1 §1부 "투자에서 가장 큰 적은 나 자신"',
  },
  {
    title: '🧭 7축 확신 게이지',
    body: 'ConvictionPanel 의 7축 (차트/유동성/정책/지정학/모멘텀/애널리스트/매크로) 정합 점수가 ±7 범위로 표시됩니다. +3 이상이면 공격적 진입, -3 이하면 방어 우선.',
    cite: 'video1 §2부 "5가지가 같은 방향" + video4 §7축',
  },
  {
    title: '📊 내 보유 비중 입력',
    body: '/plan 페이지에서 cash/nasdaq/gold 등 8자산의 현재 보유 % 를 입력하면 시스템 권고와 ≥10%p 차이 시 weekly-report 가 경고합니다.',
    cite: 'video1 §5부 "비중 기준 없으면 결국 그때그때 감정"',
  },
  {
    title: '🤖 텔레그램 봇 연결',
    body: '텔레그램에서 /status /signal NASDAQ /plan /weekly /log <ASSET> <BUY|SELL> 5종 명령 사용 가능. /log 는 즉시 trade-log 기록 + 시스템 권고 vs 사용자 행동 자동 비교.',
    cite: 'video4 §매매일지',
  },
];

export function Onboarding() {
  const [step, setStep] = useState(0);
  const [open, setOpen] = useState(false);

  useEffect(() => {
    if (typeof window === 'undefined') return;
    const done = window.localStorage.getItem('onboarding_done_v1');
    if (!done) setOpen(true);
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
    <div className="fixed inset-0 z-40 flex items-center justify-center bg-black/70 p-4">
      <div className="max-w-md w-full rounded-xl border border-cyan-700 bg-slate-950 p-6 shadow-xl">
        <div className="text-xs text-slate-400 mb-2">단계 {step + 1} / {STEPS.length}</div>
        <h2 className="text-lg font-bold text-slate-100 mb-2">{current.title}</h2>
        <p className="text-sm text-slate-200 leading-relaxed">{current.body}</p>
        <p className="text-[10px] text-slate-500 mt-2 italic">{current.cite}</p>
        <div className="mt-4 flex justify-between gap-2">
          <button onClick={close} className="text-xs text-slate-400 hover:text-slate-200">건너뛰기</button>
          <button onClick={next} className="px-3 py-1.5 rounded bg-cyan-600 hover:bg-cyan-500 text-white text-sm">
            {step < STEPS.length - 1 ? '다음 →' : '시작하기'}
          </button>
        </div>
      </div>
    </div>
  );
}
