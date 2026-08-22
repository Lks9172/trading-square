package io.macrosquare.shared.adapter.out.storage;

/** Adapter-local projection store contract for the single mutable snapshot writer. */
public interface WritableJsonEnvelopeStore extends JsonEnvelopeStore {

    void saveEnvelope(String fileName, byte[] content, String contentType);
}
