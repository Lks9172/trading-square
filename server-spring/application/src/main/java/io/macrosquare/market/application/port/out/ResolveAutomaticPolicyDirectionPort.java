package io.macrosquare.market.application.port.out;

import io.macrosquare.market.application.model.AutomaticPolicyDirection;

import java.util.Optional;

public interface ResolveAutomaticPolicyDirectionPort {
    Optional<AutomaticPolicyDirection> resolve();
}
