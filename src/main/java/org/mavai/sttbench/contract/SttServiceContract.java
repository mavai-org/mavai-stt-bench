package org.mavai.sttbench.contract;

import static org.mavai.punit.api.criterion.Criteria.empirical;
import static org.mavai.punit.api.criterion.Criteria.of;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.mavai.outcome.Outcome;
import org.mavai.punit.api.Expected;
import org.mavai.punit.api.ServiceContract;
import org.mavai.punit.api.TokenTracker;
import org.mavai.punit.api.covariate.Covariate;
import org.mavai.punit.api.covariate.CovariateCategory;
import org.mavai.punit.api.criterion.Criteria;
import org.mavai.punit.api.criterion.ValueMatcher;
import org.mavai.sttbench.eval.TranscriptNormaliser;
import org.mavai.sttbench.provider.SttProvider;
import org.mavai.sttbench.provider.SttResponse;
import org.mavai.sttbench.provider.noop.NoopSttProvider;

/**
 * The predefined PUnit service contract for Speech-to-Text benchmarking.
 *
 * <p>This is the single, opinionated contract every provider is judged
 * against. It is deliberately not generic: the criteria below encode what it
 * means for an STT system to transcribe well, expressed only as claims PUnit
 * can evaluate <em>soundly</em> across many clips and recipes.
 *
 * <p><strong>Only inferentially-sound claims are judged.</strong> Each
 * postcondition reduces a sample to a genuine pass/fail with a principled
 * boundary, so the engine's pass-rate-with-confidence-interval machinery
 * applies honestly:
 * <ul>
 *   <li><em>non-empty transcript</em> — the provider returned usable text;</li>
 *   <li><em>normalised exact match</em> — a Bernoulli outcome whose threshold
 *       (zero errors) is principled, not guessed.</li>
 * </ul>
 * The one continuous dimension judged here is <em>latency</em>, via the
 * sibling {@link #latency()} percentile criterion — a per-request quantity
 * whose distribution tail PUnit bounds soundly, and whose threshold is
 * <em>derived</em> from an empirical baseline rather than imposed up front.
 *
 * <p><strong>WER, CER and Token F1 are deliberately not judged here.</strong>
 * They are continuous accuracy metrics that PUnit cannot yet characterise
 * inferentially: per-clip they are heteroscedastic, segmentation-dependent
 * rates (WER/CER) or a non-poolable score (Token F1), and the pooled-rate
 * machinery they need does not exist in the family today. Collapsing them to
 * pass/fail at a hand-guessed threshold (the old {@code maxWer = 0.30}) would
 * teach the very anti-pattern this project exists to discourage. They survive
 * as <em>descriptive, unjudged</em> report columns computed by
 * {@code runSttBenchmark} (see
 * {@link org.mavai.sttbench.report.ExploreResult}), not as contract criteria.
 * Background: the orchestrator follow-up note
 * {@code stt-metric-characterisation-and-pooled-rate-archetype}.
 *
 * <p><strong>Reference transcripts as ground truth.</strong> The reference is
 * the text originally read to record the corpus clip. It travels on the
 * per-sample input: {@link AudioSample} implements {@link Expected}{@code
 * <String>}, so the engine reads {@link AudioSample#expected()} once per
 * sample and routes it — alongside the provider's transcript — to each
 * reference-bearing criterion's {@link ValueMatcher}. There is no shared
 * mutable state and no synthetic wrapper on the output type, which stays a
 * plain {@code String}.
 *
 * <p>The reference is carried on the sample but never passed to the provider,
 * so a provider cannot read the answer it is being judged against. Scoring is
 * literal — judged exact match, and the descriptive WER/CER/Token-F1 columns —
 * under {@link TranscriptNormaliser normalisation}, never semantic or cosine
 * similarity, because STT systems transcribe, not paraphrase.
 *
 * <p>The provider is a {@link CovariateCategory#CONFIGURATION} covariate, so
 * a baseline measured under one provider can never silently match a test run
 * under another.
 *
 * <p><strong>Skeletal.</strong> The corpus-loading wiring that builds the
 * {@link AudioSample} inputs (each request paired with its resolved reference)
 * is a Hackergarten extension point (see {@code runSttBenchmark}). The criteria
 * set is complete enough to be meaningful and intentionally leaves
 * keyword-retention criteria out by design. Computing the descriptive
 * WER/CER/Token-F1 columns (judged by nothing, observed for the report) is the
 * companion Hackergarten task on the reporting side.
 */
public final class SttServiceContract
        implements ServiceContract<String, AudioSample, String> {

    private final SttProvider provider;

    /**
     * @param providerId the {@link SttProvider#id() id} of the provider under
     *     benchmark — the {@code String} factor PUnit varies across the
     *     explore grid. Resolved to a concrete provider by
     *     {@link #resolveProvider(String)}.
     */
    public SttServiceContract(String providerId) {
        this.provider = resolveProvider(Objects.requireNonNull(providerId, "providerId"));
    }

    // Resolves the provider-id factor to a concrete provider.
    //
    // EXTENSION POINT — provider discovery. Today only the bundled no-op
    // provider is wired, looked up by its id(). Adding a provider should NOT
    // mean editing this method: the intended open mechanism is runtime
    // discovery — a ServiceLoader over SttProvider implementations (or provider
    // factories) registered on the classpath and keyed by id() — so a fork can
    // drop in a provider this harness has never heard of without touching the
    // contract. Swap the fixed list below for the discovered set when that lands.
    //
    // An unknown id is a benchmark misconfiguration, not a sample failure, so it
    // aborts the run with a thrown exception rather than travelling as an
    // Outcome (see the Outcome convention in this module's conventions).
    private static SttProvider resolveProvider(String providerId) {
        List<SttProvider> available = List.of(new NoopSttProvider());
        return available.stream()
                .filter(p -> p.id().equals(providerId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown STT provider id '" + providerId + "'; available: "
                                + available.stream().map(SttProvider::id).toList()));
    }

    @Override
    public Criteria<String> criteria() {
        return of(
                // Reference-free: needs only the produced transcript. A genuine
                // Bernoulli — the provider either returned usable text or not.
                empirical().<String>passRate()
                        .name("non-empty-transcript")
                        .satisfies("Transcript is non-empty", this::checkNonEmpty),

                // Reference-bearing: the framework supplies (expected, actual)
                // from the sample's Expected#expected() and the invoke output.
                // Exact match is the one accuracy outcome with a principled,
                // un-guessed boundary (zero errors), so its pass rate is sound.
                empirical().<String>passRate()
                        .name("normalised-exact-match")
                        .matchedBy(ExactMatcher::new));
        // WER/CER/Token-F1 are deliberately absent: see the class javadoc. They
        // are observed as descriptive report columns, never judged here.
    }


    private Outcome<Void> checkNonEmpty(String transcript) {
        return TranscriptNormaliser.normalise(transcript).isEmpty()
                ? Outcome.fail("empty-transcript", "Provider returned an empty transcript")
                : Outcome.ok();
    }

    @Override
    public List<Covariate> covariates() {
        return List.of(Covariate.custom("stt_provider", CovariateCategory.CONFIGURATION));
    }

    @Override
    public Map<String, Supplier<String>> customCovariateResolvers() {
        return Map.of("stt_provider", provider::id);
    }

    @Override
    public String id() {
        return "stt-transcription";
    }

    @Override
    public Outcome<String> invoke(AudioSample sample, TokenTracker tracker) {
        Outcome<SttResponse> response = provider.transcribe(sample.request());
        return response.map(SttResponse::transcript);
    }

    // ── Value matcher: (expected reference, actual transcript) -> Outcome ──
    // The eval metric normalises internally; its compute(reference, hypothesis)
    // signature maps directly onto the matcher's (expected, actual) pair.
    //
    // Only exact match lives here. WER/CER/Token-F1 matchers were removed: they
    // collapsed a continuous, inferentially-intractable metric to pass/fail at a
    // guessed threshold. Those metrics are now computed descriptively on the
    // reporting side (see the class javadoc and the orchestrator follow-up note).

    private record ExactMatcher() implements ValueMatcher<String> {
        @Override
        public Outcome<Void> match(String expected, String actual) {
            return TranscriptNormaliser.normalise(expected)
                    .equals(TranscriptNormaliser.normalise(actual))
                    ? Outcome.ok()
                    : Outcome.fail("no-exact-match",
                            "Normalised transcript does not match reference");
        }
    }

}
