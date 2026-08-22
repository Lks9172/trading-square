"use client";

export default function ResearchError({ reset }: { reset: () => void }) {
  return (
    <main className="mx-auto flex min-h-[60vh] max-w-3xl items-center justify-center px-6 py-16">
      <section className="w-full rounded-3xl border border-amber-400/25 bg-amber-500/5 p-8 text-center">
        <p className="text-xs font-semibold uppercase tracking-[0.24em] text-amber-300">Temporary delay</p>
        <h1 className="mt-3 text-2xl font-bold text-white">분석 데이터를 다시 불러오는 중입니다</h1>
        <p className="mt-3 text-sm leading-6 text-slate-300">
          서버 재시작이나 일시적인 수집 지연일 수 있습니다. 저장된 데이터는 삭제되지 않았습니다.
        </p>
        <button
          type="button"
          onClick={reset}
          className="mt-6 min-h-11 cursor-pointer rounded-xl bg-amber-300 px-5 py-2.5 text-sm font-bold text-slate-950 transition hover:bg-amber-200 active:scale-[0.98]"
        >
          다시 불러오기
        </button>
      </section>
    </main>
  );
}
