/**
 * 수동 span 생성 헬퍼. telemetry.ts 가 초기화 실패해도 NoOp tracer 가 반환되므로
 * 호출 측은 try/catch 없이 그대로 쓸 수 있다.
 */
import { trace, SpanStatusCode, Span, Attributes } from '@opentelemetry/api';

const tracer = trace.getTracer('macrosquare-server');

export async function withSpan<T>(
  name: string,
  fn: (span: Span) => Promise<T>,
  attributes?: Attributes,
): Promise<T> {
  return tracer.startActiveSpan(name, { attributes }, async (span) => {
    try {
      const result = await fn(span);
      span.setStatus({ code: SpanStatusCode.OK });
      return result;
    } catch (error) {
      span.recordException(error as Error);
      span.setStatus({
        code: SpanStatusCode.ERROR,
        message: error instanceof Error ? error.message : String(error),
      });
      throw error;
    } finally {
      span.end();
    }
  });
}

export function currentSpan(): Span | undefined {
  return trace.getActiveSpan();
}

export { SpanStatusCode };
