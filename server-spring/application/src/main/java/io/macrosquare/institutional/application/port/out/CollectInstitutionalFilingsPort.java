package io.macrosquare.institutional.application.port.out;

import io.macrosquare.institutional.domain.model.InstitutionalFiling;
import io.macrosquare.institutional.domain.model.InstitutionalManager;

import java.util.List;

public interface CollectInstitutionalFilingsPort {
    List<InstitutionalFiling> collect(InstitutionalManager manager, int filingLimit);
}
