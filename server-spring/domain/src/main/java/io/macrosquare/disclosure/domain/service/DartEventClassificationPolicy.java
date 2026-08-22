package io.macrosquare.disclosure.domain.service;

import io.macrosquare.disclosure.domain.model.DartEventType;

import java.util.Locale;

public final class DartEventClassificationPolicy {

    public DartEventType classify(String reportName) {
        var value = reportName == null ? "" : reportName.toLowerCase(Locale.KOREAN);
        if (contains(value, "합병", "분할", "주식교환", "영업양수", "영업양도", "타법인주식")) {
            return DartEventType.MERGER_ACQUISITION;
        }
        if (contains(value, "대표이사", "임원", "최대주주", "경영진")) return DartEventType.EXECUTIVE_CHANGE;
        if (contains(value, "유상증자", "무상증자", "전환사채", "신주인수권", "자기주식", "감자")) {
            return DartEventType.CAPITAL_ACTION;
        }
        if (contains(value, "소송", "횡령", "배임", "가처분", "과징금")) return DartEventType.LITIGATION;
        if (contains(value, "회생", "파산", "구조조정", "영업정지")) return DartEventType.RESTRUCTURING;
        if (contains(value, "사업보고서", "반기보고서", "분기보고서", "잠정실적", "매출액", "손익")) {
            return DartEventType.EARNINGS;
        }
        return DartEventType.OTHER;
    }

    private static boolean contains(String value, String... words) {
        for (var word : words) if (value.contains(word)) return true;
        return false;
    }
}
