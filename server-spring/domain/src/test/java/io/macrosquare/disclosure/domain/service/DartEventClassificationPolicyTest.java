package io.macrosquare.disclosure.domain.service;

import io.macrosquare.disclosure.domain.model.DartEventType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DartEventClassificationPolicyTest {

    private final DartEventClassificationPolicy policy = new DartEventClassificationPolicy();

    @Test
    void classifiesMaterialKoreanDisclosureNamesWithoutTransportTypes() {
        assertEquals(DartEventType.MERGER_ACQUISITION, policy.classify("회사합병 결정"));
        assertEquals(DartEventType.EXECUTIVE_CHANGE, policy.classify("대표이사 변경"));
        assertEquals(DartEventType.CAPITAL_ACTION, policy.classify("유상증자 결정"));
        assertEquals(DartEventType.LITIGATION, policy.classify("횡령ㆍ배임 혐의 발생"));
        assertEquals(DartEventType.RESTRUCTURING, policy.classify("회생절차 개시신청"));
        assertEquals(DartEventType.EARNINGS, policy.classify("분기보고서 (2026.03)"));
        assertEquals(DartEventType.OTHER, policy.classify("기업설명회 개최"));
    }
}
