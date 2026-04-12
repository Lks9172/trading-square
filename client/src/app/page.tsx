import { Dashboard } from "@/components/Dashboard";

export const dynamic = "force-dynamic";

const API_URL = process.env.NEXT_PUBLIC_API_URL || "http://localhost:5846";

async function fetchSnapshot() {
  try {
    const res = await fetch(`${API_URL}/api/snapshot`, {
      next: { revalidate: 300 },
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
