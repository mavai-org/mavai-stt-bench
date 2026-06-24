package org.mavai.sttbench.contract;

import java.util.Objects;
import org.mavai.punit.api.Expected;
import org.mavai.punit.api.criterion.ValueMatcher;
import org.mavai.sttbench.provider.SttRequest;

/**
 * Per-sample input for the {@link SttServiceContract}: the provider-facing
 * {@link SttRequest} paired with the ground-truth reference transcript.
 *
 * <p>Implements {@link Expected}{@code <String>} so the framework routes
 * {@link #expected()} to each reference-bearing criterion's
 * {@link ValueMatcher} on every sample. The reference is exposed here but
 * never passed to the provider — {@link SttServiceContract#invoke} hands the
 * provider only {@link #request()} — so a provider cannot read the answer it
 * is judged against.
 *
 * @param request   the provider-facing request (clip, recipe, audio path)
 * @param reference the ground-truth transcript read to record the clip
 */
public record AudioSample(SttRequest request, String reference) implements Expected<String> {

    public AudioSample {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(reference, "reference");
    }

    @Override
    public String expected() {
        return reference;
    }
}
