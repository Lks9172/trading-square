import { CompanyFilingEvent } from '../../types/fundamentals';
import { fetchSecSubmissions } from './submissions';

export async function fetchRecentCompanyFilings(cik: string, limit = 10): Promise<CompanyFilingEvent[]> {
  const submissions = await fetchSecSubmissions(cik);
  return submissions.filings.slice(0, limit);
}

