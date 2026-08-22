export function formatKstDateTime(value: string | number | Date) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '-';

  // SSR(Node/UTC)와 브라우저(KST)의 Intl 구현 차이가 hydration text
  // mismatch를 만들지 않도록 KST를 산술 변환한 뒤 고정 형식으로 출력한다.
  const kst = new Date(date.getTime() + 9 * 60 * 60 * 1000);
  const pad = (part: number) => String(part).padStart(2, '0');
  return `${kst.getUTCFullYear()}. ${pad(kst.getUTCMonth() + 1)}. ${pad(kst.getUTCDate())}. ${pad(kst.getUTCHours())}:${pad(kst.getUTCMinutes())}:${pad(kst.getUTCSeconds())} KST`;
}
