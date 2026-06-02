import axios from 'axios';

export const SEC_BASE_HEADERS = {
  'User-Agent': process.env.SEC_USER_AGENT || 'MacroSquare research contact macrosquare@example.com',
  Accept: 'application/json, text/plain, */*',
};

export async function secGetJson<T>(url: string): Promise<T> {
  const { data } = await axios.get<T>(url, {
    headers: SEC_BASE_HEADERS,
    timeout: 15000,
  });
  return data;
}

export function normalizeCik(cik: string | number): string {
  const digits = String(cik).replace(/\D+/g, '');
  return digits.padStart(10, '0');
}

