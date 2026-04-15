/**
 * OpenTelemetry tracing bootstrap.
 *
 * index.ts 의 **가장 상단** 에서 `import './telemetry'` 로 불러와야 함.
 * auto-instrumentations 가 axios/http/express 등 다른 모듈 require 이전에
 * 훅을 설치해야 동작하기 때문.
 *
 * 실패해도 애플리케이션은 계속 동작해야 한다 — 모든 초기화는 try/catch 로 감싼다.
 */
import { diag, DiagConsoleLogger, DiagLogLevel } from '@opentelemetry/api';
import { NodeSDK } from '@opentelemetry/sdk-node';
import { getNodeAutoInstrumentations } from '@opentelemetry/auto-instrumentations-node';
import { OTLPTraceExporter } from '@opentelemetry/exporter-trace-otlp-http';
import { resourceFromAttributes } from '@opentelemetry/resources';
import { ATTR_SERVICE_NAME, ATTR_SERVICE_VERSION } from '@opentelemetry/semantic-conventions';

const OTEL_ENDPOINT = process.env.OTEL_EXPORTER_OTLP_ENDPOINT || 'http://localhost:4318';
const SERVICE_NAME = process.env.OTEL_SERVICE_NAME || 'macrosquare-server';
const SERVICE_VERSION = process.env.OTEL_SERVICE_VERSION || '1.0.0';

// OTEL_DEBUG=1 로만 verbose log — 기본은 에러만.
if (process.env.OTEL_DEBUG === '1') {
  diag.setLogger(new DiagConsoleLogger(), DiagLogLevel.DEBUG);
}

let sdk: NodeSDK | null = null;

try {
  sdk = new NodeSDK({
    resource: resourceFromAttributes({
      [ATTR_SERVICE_NAME]: SERVICE_NAME,
      [ATTR_SERVICE_VERSION]: SERVICE_VERSION,
    }),
    traceExporter: new OTLPTraceExporter({
      url: `${OTEL_ENDPOINT.replace(/\/$/, '')}/v1/traces`,
    }),
    instrumentations: [
      getNodeAutoInstrumentations({
        // fs 는 노이즈가 너무 많아 비활성화
        '@opentelemetry/instrumentation-fs': { enabled: false },
      }),
    ],
  });

  sdk.start();
  console.log(`[telemetry] OTel tracing 활성 — endpoint=${OTEL_ENDPOINT} service=${SERVICE_NAME}`);
} catch (error) {
  // telemetry 초기화 실패가 애플리케이션 기동을 막아서는 안 된다.
  console.warn('[telemetry] OTel SDK 초기화 실패 — tracing 없이 계속 진행:', error);
  sdk = null;
}

async function shutdown() {
  if (!sdk) return;
  try {
    await sdk.shutdown();
    console.log('[telemetry] OTel SDK shutdown 완료');
  } catch (error) {
    console.warn('[telemetry] OTel SDK shutdown 실패:', error);
  }
}

process.on('SIGTERM', () => {
  void shutdown();
});
process.on('SIGINT', () => {
  void shutdown();
});
