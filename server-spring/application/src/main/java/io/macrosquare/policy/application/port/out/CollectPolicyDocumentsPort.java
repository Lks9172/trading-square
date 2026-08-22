package io.macrosquare.policy.application.port.out;

import io.macrosquare.policy.domain.model.PolicyDocument;

import java.util.List;

public interface CollectPolicyDocumentsPort {
    List<PolicyDocument> collect(int maximumDocuments);
}
