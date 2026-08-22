package io.macrosquare.integrity.domain;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Objects;

/**
 * Pure recurrence policy for incidents that previously produced stale or
 * invalid investment decisions.
 */
public final class DataIntegrityPolicy {

    private static final int EXPECTED_COLLECTION_STATUS_ROWS = 6;
    private static final int EXPECTED_SECTOR_PRICE_SERIES = 16;
    private final int expectedCompanyUniverse;
    private final Duration maximumSummaryAge;

    public DataIntegrityPolicy(int expectedCompanyUniverse, Duration maximumSummaryAge) {
        if (expectedCompanyUniverse < 1) throw new IllegalArgumentException("expected universe must be positive");
        if (maximumSummaryAge == null || maximumSummaryAge.isZero() || maximumSummaryAge.isNegative()) {
            throw new IllegalArgumentException("maximum summary age must be positive");
        }
        this.expectedCompanyUniverse = expectedCompanyUniverse;
        this.maximumSummaryAge = maximumSummaryAge;
    }

    public DataIntegrityReport evaluate(DataIntegrityEvidence evidence) {
        Objects.requireNonNull(evidence, "evidence");
        var violations = new ArrayList<DataIntegrityViolation>();
        exact(violations, evidence, IntegrityMetric.COMPANY_UNIVERSE_ROWS,
                expectedCompanyUniverse, "현재 기업 유니버스 행 수가 계약과 다름");
        exact(violations, evidence, IntegrityMetric.COMPANY_CURRENT_CALCULATION_ROWS,
                expectedCompanyUniverse, "구버전 계산 행이 현재 조회에 섞임");
        minimum(violations, evidence, IntegrityMetric.COMPANY_COMPARABLE_SCORE_ROWS,
                minimumComparableCompanies(), "비교 가능한 기업 점수가 유니버스의 80% 미만으로 급감함");
        exact(violations, evidence, IntegrityMetric.COMPANY_PRICE_SIGNAL_ROWS,
                expectedCompanyUniverse, "현재 가격·바닥 신호가 없는 기업 행이 발생함");
        exact(violations, evidence, IntegrityMetric.ANALYST_SERIES_ROWS,
                expectedCompanyUniverse, "컨센서스 시계열 유니버스 행 수가 계약과 다름");
        exact(violations, evidence, IntegrityMetric.CANONICAL_MRSH_ROWS,
                1, "MRSH 정식 티커가 정확히 한 행이 아님");
        exact(violations, evidence, IntegrityMetric.MARKET_COLLECTION_STATUS_ROWS,
                EXPECTED_COLLECTION_STATUS_ROWS, "필수 수집기 상태 행이 누락됨");
        exact(violations, evidence, IntegrityMetric.SECTOR_PRICE_SERIES_ROWS,
                EXPECTED_SECTOR_PRICE_SERIES, "주도 섹터 가격 시계열이 누락됨");
        exact(violations, evidence, IntegrityMetric.SECTOR_HISTORY_READY_ROWS,
                EXPECTED_SECTOR_PRICE_SERIES, "주도 섹터 계산에 필요한 1년 이상 가격 이력이 누락됨");
        exact(violations, evidence, IntegrityMetric.SECTOR_BENCHMARK_READY_ROWS,
                1, "S&P 500 상대강도 벤치마크 이력이 누락되거나 오래됨");
        exact(violations, evidence, IntegrityMetric.SECTOR_TOTAL_RETURN_SERIES_ROWS,
                EXPECTED_SECTOR_PRICE_SERIES, "배당 반영 주도 섹터 총수익률 시계열이 누락됨");
        exact(violations, evidence, IntegrityMetric.SECTOR_TOTAL_RETURN_HISTORY_READY_ROWS,
                EXPECTED_SECTOR_PRICE_SERIES, "7년 워크포워드에 필요한 총수익률 이력이 누락됨");
        exact(violations, evidence, IntegrityMetric.SECTOR_TOTAL_RETURN_BENCHMARK_READY_ROWS,
                1, "SPY 총수익률 벤치마크 이력이 누락되거나 오래됨");
        exact(violations, evidence, IntegrityMetric.CURRENT_SECTOR_ROTATION_READY_ROWS,
                1, "현재 V3 주도 섹터 원장에 완전한 11개 섹터 스냅샷이 없음");

        var zeroExpected = new EnumMap<IntegrityMetric, String>(IntegrityMetric.class);
        zeroExpected.put(IntegrityMetric.INVALID_COMPANY_SCORE_ROWS, "0~100 범위를 벗어난 기업 점수");
        zeroExpected.put(IntegrityMetric.INCOMPLETE_COMPANY_SCORE_ROWS,
                "일부 축만 저장됐거나 평가 자격 없이 노출된 기업 점수");
        zeroExpected.put(IntegrityMetric.FUTURE_COMPANY_DATE_ROWS, "미래 시점 기업 데이터");
        zeroExpected.put(IntegrityMetric.NONCURRENT_SCORED_ROWS, "최신 공시 미반영 상태에 남은 기업/B 점수");
        zeroExpected.put(IntegrityMetric.BUY_WITHOUT_EVIDENCE_ROWS, "현재 근거 없이 생성된 BUY 액션");
        zeroExpected.put(IntegrityMetric.INCOMPLETE_PRICE_SIGNAL_ROWS, "부분 저장된 가격·바닥 신호 묶음");
        zeroExpected.put(IntegrityMetric.UNAVAILABLE_COMPANY_ROWS, "현재 원천 검증 실패로 격리된 기업");
        zeroExpected.put(IntegrityMetric.RETIRED_OR_ALIAS_COMPANY_ROWS, "폐지·구 티커가 현재 유니버스에 잔존");
        zeroExpected.put(IntegrityMetric.HARD_COLLECTION_FAILURE_ROWS, "의사결정 원천 수집 실패");
        zeroExpected.put(IntegrityMetric.STALE_COLLECTION_ROWS, "수집 주기를 넘긴 원천 상태");
        zeroExpected.put(IntegrityMetric.STALE_SECTOR_PRICE_ROWS, "7일 넘게 갱신되지 않은 주도 섹터 가격");
        zeroExpected.put(IntegrityMetric.MISALIGNED_SECTOR_PRICE_ROWS,
                "S&P 500과 최신 거래일이 어긋난 주도 섹터 가격");
        zeroExpected.put(IntegrityMetric.SECTOR_PRICE_DISCONTINUITY_ROWS,
                "최근 주도 섹터 가격에 미조정 분할로 의심되는 45% 초과 불연속");
        zeroExpected.put(IntegrityMetric.STALE_SECTOR_TOTAL_RETURN_ROWS,
                "7일 넘게 갱신되지 않은 주도 섹터 총수익률");
        zeroExpected.put(IntegrityMetric.MISALIGNED_SECTOR_TOTAL_RETURN_ROWS,
                "SPY와 최신 거래일이 어긋난 주도 섹터 총수익률");
        zeroExpected.put(IntegrityMetric.SECTOR_TOTAL_RETURN_DISCONTINUITY_ROWS,
                "최근 조정주가에 45% 초과 불연속이 발생한 주도 섹터");
        zeroExpected.put(IntegrityMetric.INVALID_SECTOR_ROTATION_RUN_ROWS,
                "V3 주도 섹터 원장에 11개 섹터 원자성 계약을 위반한 실행");
        zeroExpected.put(IntegrityMetric.FUTURE_MARKET_ROWS, "미래 시점 시장 관측값");
        zeroExpected.put(IntegrityMetric.NONFINITE_MARKET_ROWS, "NaN/무한대 시장 관측값");
        zeroExpected.put(IntegrityMetric.DUPLICATE_MARKET_ROWS, "중복 시장 관측값");
        zeroExpected.put(IntegrityMetric.FUTURE_ANALYST_ROWS, "미래 시점 컨센서스 관측값");
        zeroExpected.put(IntegrityMetric.INVALID_ANALYST_ROWS, "범위 오류 컨센서스 값");
        zeroExpected.put(IntegrityMetric.DUPLICATE_ANALYST_ROWS, "중복 컨센서스 관측값");
        zeroExpected.put(IntegrityMetric.STALE_ANALYST_SERIES_ROWS,
                "미래 시각이거나 2시간 넘게 갱신되지 않은 컨센서스 시계열");
        zeroExpected.put(IntegrityMetric.EMPTY_LATEST_ANALYST_ROWS,
                "공급자 장애값으로 의심되는 빈 최신 컨센서스 관측");
        zeroExpected.put(IntegrityMetric.INVALID_13F_HOLDING_ROWS, "0/비정상 13F 보유값");
        zeroExpected.put(IntegrityMetric.INVALID_13F_DATE_ROWS, "역전·미래 13F 공시일");
        zeroExpected.put(IntegrityMetric.SUSPICIOUS_13F_UNIT_GROUPS, "달러/천달러 단위 오류 의심 13F 그룹");
        zeroExpected.put(IntegrityMetric.DANGLING_OBJECT_POINTER_ROWS, "실체가 없는 오브젝트 포인터");
        zeroExpected.put(IntegrityMetric.CANDIDATE_DRIFT_ROWS, "알림 후보와 현재 기업 점수 불일치");
        zeroExpected.put(IntegrityMetric.OUTBOX_RETRY_ROWS, "재시도 중인 텔레그램 outbox");
        zeroExpected.put(IntegrityMetric.OUTBOX_DEAD_ROWS, "최종 실패한 텔레그램 outbox");
        zeroExpected.put(IntegrityMetric.OUTBOX_STUCK_ROWS, "지연·만료된 텔레그램 outbox");
        zeroExpected.forEach((metric, description) -> zero(violations, evidence, metric, description));

        // One freshly updated ticker must never hide the rest of a partially
        // failed universe refresh, so the oldest current projection is used.
        var oldest = evidence.oldestCompanySummaryAt();
        if (oldest == null || oldest.plus(maximumSummaryAge).isBefore(evidence.observedAt())) {
            var age = oldest == null ? Long.MAX_VALUE
                    : Math.max(0, Duration.between(oldest, evidence.observedAt()).toSeconds());
            violations.add(new DataIntegrityViolation(
                    "COMPANY_SUMMARY_STALE", age, maximumSummaryAge.toSeconds(),
                    "기업 요약 전량 갱신 시각이 허용 범위를 넘김"));
        }
        return new DataIntegrityReport(
                evidence.observedAt(), violations, evidence.hardCollectionSources());
    }

    private static void exact(
            java.util.List<DataIntegrityViolation> target,
            DataIntegrityEvidence evidence,
            IntegrityMetric metric,
            long expected,
            String description
    ) {
        var actual = evidence.metric(metric);
        if (actual != expected) target.add(new DataIntegrityViolation(metric.name(), actual, expected, description));
    }

    private static void zero(
            java.util.List<DataIntegrityViolation> target,
            DataIntegrityEvidence evidence,
            IntegrityMetric metric,
            String description
    ) {
        exact(target, evidence, metric, 0, description);
    }

    private int minimumComparableCompanies() {
        return (expectedCompanyUniverse * 80 + 99) / 100;
    }

    private static void minimum(
            java.util.List<DataIntegrityViolation> target,
            DataIntegrityEvidence evidence,
            IntegrityMetric metric,
            long minimum,
            String description
    ) {
        var actual = evidence.metric(metric);
        if (actual < minimum) {
            target.add(new DataIntegrityViolation(metric.name(), actual, minimum, description));
        }
    }
}
