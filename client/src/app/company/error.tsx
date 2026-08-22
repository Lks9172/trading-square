"use client";

export default function CompanyError({ reset }: { reset: () => void }) {
  return (
    <main className="mx-auto flex min-h-[60vh] max-w-3xl items-center justify-center px-6 py-16">
      <section className="w-full rounded-3xl border border-sky-400/25 bg-sky-500/5 p-8 text-center">
        <p className="text-xs font-semibold uppercase tracking-[0.24em] text-sky-300">Temporary delay</p>
        <h1 className="mt-3 text-2xl font-bold text-white">기업 분석을 다시 불러오는 중입니다</h1>
        <p className="mt-3 text-sm leading-6 text-slate-300">
          일시적인 API 지연입니다. 기존 분석 데이터와 히스토리는 그대로 보존됩니다.
        </p>
        <button
          type="button"
          onClick={reset}
          className="mt-6 min-h-11 cursor-pointer rounded-xl bg-sky-300 px-5 py-2.5 text-sm font-bold text-slate-950 transition hover:bg-sky-200 active:scale-[0.98]"
        >
          다시 불러오기
        </button>
      </section>
    </main>
  );
}
