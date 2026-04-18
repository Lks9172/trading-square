import fs from 'fs';
import path from 'path';
import pino from 'pino';

const LOG_LEVEL = process.env.LOG_LEVEL || 'info';
const LOG_DIR = process.env.LOG_DIR || '/app/logs';
const LOG_FILE = path.join(LOG_DIR, 'server.log');

const streams: pino.StreamEntry[] = [{ stream: process.stdout }];

try {
  fs.mkdirSync(LOG_DIR, { recursive: true });
  streams.push({ stream: pino.destination({ dest: LOG_FILE, sync: false }) });
} catch (error) {
  console.warn('[logger] file destination init failed, stdout only:', error);
}

export const logger = pino(
  {
    level: LOG_LEVEL,
    base: {
      service: 'macrosquare-server',
      env: process.env.NODE_ENV || 'development',
    },
    timestamp: pino.stdTimeFunctions.isoTime,
  },
  pino.multistream(streams),
);

export function childLogger(bindings: Record<string, unknown>) {
  return logger.child(bindings);
}

export function serializeError(error: unknown) {
  if (error instanceof Error) {
    const anyErr = error as Error & {
      code?: string;
      response?: { status?: number; data?: unknown };
      cause?: unknown;
    };
    return {
      name: anyErr.name,
      message: anyErr.message,
      stack: anyErr.stack,
      code: anyErr.code,
      status: anyErr.response?.status,
      response: anyErr.response?.data,
      cause: anyErr.cause instanceof Error ? anyErr.cause.message : anyErr.cause,
    };
  }
  return { message: String(error) };
}
