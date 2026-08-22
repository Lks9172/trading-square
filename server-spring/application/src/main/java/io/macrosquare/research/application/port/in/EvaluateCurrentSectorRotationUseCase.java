package io.macrosquare.research.application.port.in;

import io.macrosquare.research.application.model.CurrentSectorRotationAssessment;

@FunctionalInterface
public interface EvaluateCurrentSectorRotationUseCase {
    CurrentSectorRotationAssessment evaluate(CurrentSectorRotationCommand command);
}
