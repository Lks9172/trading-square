import type { Metadata } from "next";
import { Geist, Geist_Mono } from "next/font/google";
import "./globals.css";
import { SmartLink } from "@/components/SmartLink";
import { WebVitalsReporter } from "@/components/WebVitalsReporter";

const geistSans = Geist({
  variable: "--font-geist-sans",
  subsets: ["latin"],
});

const geistMono = Geist_Mono({
  variable: "--font-geist-mono",
  subsets: ["latin"],
});

export const metadata: Metadata = {
  title: "MacroSquare",
  description: "매크로 기반 포트폴리오 판단 시스템",
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html
      lang="ko"
      className={`${geistSans.variable} ${geistMono.variable} h-full antialiased`}
    >
      <body className="min-h-full flex flex-col bg-[#0a0a0a] text-[#ededed]">
        <WebVitalsReporter />
        <nav className="border-b border-slate-800 bg-slate-950/70 backdrop-blur sticky top-0 z-10">
          <div className="max-w-7xl mx-auto px-4 py-2 flex items-center gap-4 text-sm">
            <SmartLink href="/" className="font-semibold text-cyan-300 cursor-pointer hover:text-cyan-200">📊 Dashboard</SmartLink>
            <SmartLink href="/plan" className="text-slate-300 hover:text-slate-100">🧭 My Plan</SmartLink>
            <SmartLink href="/research" className="text-slate-300 hover:text-slate-100">🔎 Research</SmartLink>
          </div>
        </nav>
        {children}
      </body>
    </html>
  );
}
