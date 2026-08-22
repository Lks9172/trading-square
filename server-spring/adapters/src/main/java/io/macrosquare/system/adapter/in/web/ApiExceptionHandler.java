package io.macrosquare.system.adapter.in.web;

import io.macrosquare.crypto.application.port.in.CryptoSymbolNotFoundException;
import io.macrosquare.crypto.application.port.out.CryptoResearchUnavailableException;
import io.macrosquare.company.application.port.out.CompanyReadUnavailableException;
import io.macrosquare.company.application.port.out.CompanyAnalystEvidenceUnavailableException;
import io.macrosquare.company.application.port.out.CompanyFundamentalsUnavailableException;
import io.macrosquare.company.application.port.out.CompanyIdentityUnavailableException;
import io.macrosquare.company.application.port.out.CompanyMarketQuoteUnavailableException;
import io.macrosquare.company.application.port.out.CompanyPriceHistoryUnavailableException;
import io.macrosquare.company.application.port.out.CompanyResearchParityUnavailableException;
import io.macrosquare.company.application.port.out.CompanySubmissionsUnavailableException;
import io.macrosquare.company.application.port.out.CompanyFilingDetailUnavailableException;
import io.macrosquare.company.application.port.out.CompanyFilingDocumentUnavailableException;
import io.macrosquare.company.application.port.in.CompanyTickerNotFoundException;
import io.macrosquare.research.application.port.out.ResearchSnapshotUnavailableException;
import io.macrosquare.research.application.port.out.ResearchCatalogUnavailableException;
import io.macrosquare.research.application.port.in.NarrativeThemeNotFoundException;
import io.macrosquare.research.application.port.in.ResearchSectorNotFoundException;
import io.macrosquare.research.application.port.in.ResearchThemeNotFoundException;
import io.macrosquare.research.application.port.in.CurrentSectorRotationUnavailableException;
import io.macrosquare.market.application.port.out.MarketReadUnavailableException;
import io.macrosquare.execution.application.port.out.InvestmentExecutionPersistenceException;
import io.macrosquare.compatibility.application.port.in.SupplementalResourceNotFoundException;
import io.macrosquare.compatibility.application.port.out.SupplementalApiUnavailableException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public final class ApiExceptionHandler {

    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> badRequest(Exception error) {
        var response = new LinkedHashMap<String, String>();
        response.put("error", error.getMessage() == null ? "Bad request" : error.getMessage());
        return response;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> malformedPayload() {
        return Map.of("error", "Malformed or unsupported request payload");
    }

    @ExceptionHandler(NarrativeThemeNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> narrativeThemeNotFound(NarrativeThemeNotFoundException error) {
        return Map.of("error", error.getMessage());
    }

    @ExceptionHandler({ResearchThemeNotFoundException.class, ResearchSectorNotFoundException.class})
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> researchDetailNotFound(RuntimeException error) {
        return Map.of("error", error.getMessage());
    }

    @ExceptionHandler(CryptoSymbolNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> cryptoSymbolNotFound(CryptoSymbolNotFoundException error) {
        return Map.of("error", error.getMessage());
    }

    @ExceptionHandler(CompanyTickerNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> companyTickerNotFound(CompanyTickerNotFoundException error) {
        return Map.of("error", error.getMessage());
    }

    @ExceptionHandler(ResearchSnapshotUnavailableException.class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    public Map<String, String> legacySnapshotUnavailable() {
        return Map.of("error", "Legacy research snapshot is temporarily unavailable");
    }

    @ExceptionHandler(ResearchCatalogUnavailableException.class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    public Map<String, String> legacyResearchCatalogUnavailable() {
        return Map.of("error", "Legacy research catalog is temporarily unavailable");
    }

    @ExceptionHandler(CurrentSectorRotationUnavailableException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Map<String, String> currentSectorRotationUnavailable() {
        return Map.of("error", "Current sector rotation data is temporarily unavailable");
    }

    @ExceptionHandler(CryptoResearchUnavailableException.class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    public Map<String, String> legacyCryptoResearchUnavailable() {
        return Map.of("error", "Legacy crypto research is temporarily unavailable");
    }

    @ExceptionHandler(CompanyReadUnavailableException.class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    public Map<String, String> legacyCompanyReadUnavailable() {
        return Map.of("error", "Legacy company data is temporarily unavailable");
    }

    @ExceptionHandler(MarketReadUnavailableException.class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    public Map<String, String> legacyMarketReadUnavailable() {
        return Map.of("error", "Legacy snapshot/history data is temporarily unavailable");
    }

    @ExceptionHandler({
            CompanyFundamentalsUnavailableException.class,
            CompanyIdentityUnavailableException.class,
            CompanyMarketQuoteUnavailableException.class,
            CompanyPriceHistoryUnavailableException.class,
            CompanySubmissionsUnavailableException.class,
            CompanyFilingDetailUnavailableException.class,
            CompanyFilingDocumentUnavailableException.class,
            CompanyAnalystEvidenceUnavailableException.class,
            CompanyResearchParityUnavailableException.class
    })
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    public Map<String, String> companyResearchParityUnavailable() {
        return Map.of("error", "Company research parity data is temporarily unavailable");
    }

    @ExceptionHandler(InvestmentExecutionPersistenceException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Map<String, String> investmentExecutionPersistenceFailure() {
        return Map.of("error", "Investment execution data could not be persisted");
    }

    @ExceptionHandler(SupplementalResourceNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> supplementalResourceNotFound(SupplementalResourceNotFoundException error) {
        return Map.of("error", error.getMessage());
    }

    @ExceptionHandler(SupplementalApiUnavailableException.class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    public Map<String, String> supplementalApiUnavailable() {
        return Map.of("error", "Supplemental market data is temporarily unavailable");
    }
}
