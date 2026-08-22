package io.macrosquare.institutional.adapter.out.persistence;

import io.macrosquare.institutional.application.port.out.InstitutionalSecurityIdentityRepository;
import io.macrosquare.institutional.domain.model.InstitutionalSecurityIdentity;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class InMemoryInstitutionalSecurityIdentityRepository
        implements InstitutionalSecurityIdentityRepository {

    private final List<InstitutionalSecurityIdentity> values = new ArrayList<>();

    @Override
    public synchronized int savePointInTime(List<InstitutionalSecurityIdentity> identities) {
        for (var identity : identities) {
            var active = values.stream()
                    .filter(value -> value.cusip().equals(identity.cusip()) && value.validTo() == null)
                    .max(Comparator.comparing(InstitutionalSecurityIdentity::validFrom))
                    .orElse(null);
            if (active != null && active.ticker().equals(identity.ticker())) continue;
            if (active != null && identity.validFrom().isAfter(active.validFrom())) {
                values.remove(active);
                values.add(new InstitutionalSecurityIdentity(
                        active.cusip(), active.ticker(), active.cik(), active.issuer(), active.sectorKey(),
                        active.validFrom(), identity.validFrom().minusDays(1), active.confidence(), active.source()));
            }
            values.removeIf(value -> value.cusip().equals(identity.cusip())
                    && value.validFrom().equals(identity.validFrom()));
            values.add(identity);
        }
        return identities.size();
    }

    @Override
    public synchronized Map<String, InstitutionalSecurityIdentity> loadActiveOn(LocalDate reportPeriod) {
        var result = new LinkedHashMap<String, InstitutionalSecurityIdentity>();
        values.stream().filter(value -> value.activeOn(reportPeriod))
                .sorted(Comparator.comparing(InstitutionalSecurityIdentity::validFrom).reversed())
                .forEach(value -> result.putIfAbsent(value.cusip(), value));
        return Map.copyOf(result);
    }
}
