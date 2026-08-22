package io.macrosquare.research.application.model;

/** A company is valid in the SEC ticker directory but has no usable SIC taxonomy. */
public final class PeerTaxonomyUnavailableException extends RuntimeException {

    public PeerTaxonomyUnavailableException(String message) {
        super(message);
    }
}
