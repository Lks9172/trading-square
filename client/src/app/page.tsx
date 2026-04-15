import { Dashboard } from "@/components/Dashboard";

export const dynamic = "force-dynamic";

const SSR_API_URL = process.env.SSR_API_URL || "http://localhost:5846";
const API_URL = process.env.NEXT_PUBLIC_API_URL || "http://localhost:5846";

async function fetchSnapshot() {
  try {
    // cache: 'no-store' — force-dynamic 과 함께 SSR 매 요청마다 최신 스냅샷.
    // 기존 `next.revalidate: 300` 은 컨테이너 재배포 직후 부트스트랩 기간의
    // null/빈 snapshot 을 5분간 캐싱해 대시보드에 "-" 만 보이게 하던 원인.
    const res = await fetch(`${SSR_API_URL}/api/snapshot`, {
      cache: 'no-store',
    });
    if (!res.ok) return null;
    return res.json();
  } catch {
    return null;
  }
}

export default async function Home() {
  const snapshot = await fetchSnapshot();

  return (
    <main className="flex-1 p-4 md:p-6 max-w-7xl mx-auto w-full">
      <Dashboard snapshot={snapshot} />
    </main>
  );
}
