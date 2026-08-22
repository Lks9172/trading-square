const SSR_API_URL =
  process.env.SSR_API_URL ||
  process.env.INTERNAL_API_URL ||
  (process.env.NODE_ENV === "development" ? "http://localhost:5846" : "http://macrosquare-server:5846");

type FetchServerJsonOptions = {
  revalidate?: number;
  cache?: RequestCache;
  attempts?: number;
  retryDelayMs?: number;
  timeoutMs?: number;
};

type NextFetchRequestInit = RequestInit & {
  next?: { revalidate: number };
};

const DEFAULT_ATTEMPTS = 5;
const DEFAULT_RETRY_DELAY_MS = 200;
const DEFAULT_TIMEOUT_MS = 2_500;

export class ServerApiUnavailableError extends Error {
  constructor(path: string, options?: ErrorOptions) {
    super(`Server API is temporarily unavailable: ${path}`, options);
    this.name = "ServerApiUnavailableError";
  }
}

export const SERVER_API_URL = SSR_API_URL;

export function errorMessage(error: unknown, fallback = "Proxy failed"): string {
  return error instanceof Error && error.message ? error.message : fallback;
}

export async function fetchServerJson<T>(
  path: string,
  options: FetchServerJsonOptions = {},
): Promise<T | null> {
  const {
    revalidate = 300,
    cache,
    attempts = DEFAULT_ATTEMPTS,
    retryDelayMs = DEFAULT_RETRY_DELAY_MS,
    timeoutMs = DEFAULT_TIMEOUT_MS,
  } = options;
  const normalizedAttempts = Math.max(1, Math.min(10, Math.trunc(attempts)));
  let lastError: unknown;

  for (let attempt = 0; attempt < normalizedAttempts; attempt += 1) {
    const isRetry = attempt > 0;
    const requestInit: NextFetchRequestInit = isRetry
      ? { cache: "no-store", signal: AbortSignal.timeout(timeoutMs) }
      : cache
        ? { cache, signal: AbortSignal.timeout(timeoutMs) }
        : { next: { revalidate }, signal: AbortSignal.timeout(timeoutMs) };

    try {
      const response = await fetch(`${SSR_API_URL}${path}`, requestInit);

      if (response.ok) {
        return (await response.json()) as T;
      }

      // A missing resource is a valid domain result. Retrying it would only
      // delay not-found pages and could turn a 404 into a misleading outage.
      if (response.status >= 400 && response.status < 500
        && response.status !== 408
        && response.status !== 429) {
        return null;
      }

      lastError = new Error(`Server API responded with HTTP ${response.status}`);
    } catch (error) {
      lastError = error;
    }

    if (attempt + 1 < normalizedAttempts) {
      const delayMs = Math.max(0, retryDelayMs) * (2 ** attempt);
      await new Promise((resolve) => setTimeout(resolve, delayMs));
    }
  }

  // Throwing is intentional: Next.js keeps the last successful ISR/fetch
  // result when revalidation fails. Returning null here would cache a false
  // "no data" page during a short backend restart.
  throw new ServerApiUnavailableError(path, { cause: lastError });
}
