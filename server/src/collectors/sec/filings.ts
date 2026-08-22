import { CompanyFilingEvent } from '../../types/fundamentals';
import { fetchSecSubmissions } from './submissions';

export async function fetchRecentCompanyFilings(
  cik: string,
  limit = 10,
  options?: { ticker?: string | null; maxAgeMs?: number; filingDetailMaxAgeMs?: number },
): Promise<CompanyFilingEvent[]> {
  const submissions = await fetchSecSubmissions(cik, options);
  return submissions.filings.slice(0, limit);
}
