package org.mavai.sttbench.corpus;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.mavai.outcome.Outcome;
import org.mavai.sttbench.contract.AudioSample;
import org.mavai.sttbench.provider.SttRequest;

/**
 * Reads the benchmark corpus — the ground-truth audio/transcript pairs — into
 * the {@link AudioSample} inputs the
 * {@link org.mavai.sttbench.contract.SttServiceContract} samples over.
 *
 * <p>This is the corpus-loading wiring the contract's class javadoc names as an
 * extension point. It pairs each clip under {@code <corpus>/audio/} with its
 * reference transcript {@code <corpus>/transcripts/<clip-id>.txt} by shared
 * stem, exactly the convention the corpus READMEs describe (one clip → one
 * transcript). The produced requests carry {@code recipeId = "clean"} — the
 * unmodified corpus clip — since applying degradation recipes is a separate
 * axis owned by {@code AudioVariantGenerator}.
 *
 * <p>A structurally-incomplete or unreadable corpus — a missing audio
 * directory, a clip with no transcript, an I/O error — is an <em>expected
 * failure</em>: it travels as data through {@link Outcome#fail}, named and
 * messaged, rather than as a thrown exception. Genuine defects (a {@code null}
 * argument) still throw. This mirrors {@link
 * org.mavai.sttbench.recipe.AudioRecipeLoader}.
 */
public final class CorpusLoader {

    /** Recipe id stamped on requests for the unmodified corpus clip. */
    public static final String CLEAN_RECIPE_ID = "clean";

    /** BCP-47 language tag assumed when none is supplied. The bundled corpus is English. */
    public static final String DEFAULT_LANGUAGE_TAG = "en";

    /** Classpath location of the corpus shipped with the harness. */
    public static final String BUNDLED_CORPUS_RESOURCE = "/corpus";

    /**
     * Loads the corpus bundled with the harness as a classpath resource under
     * {@value #BUNDLED_CORPUS_RESOURCE}, using the {@value #DEFAULT_LANGUAGE_TAG}
     * language tag.
     *
     * <p>Resolves the bundled corpus to a filesystem directory, so it requires
     * the resources to be exploded on disk — the case under the project's Gradle
     * workflow (run/test against {@code build/resources/main}). A fork reading a
     * corpus from elsewhere should call {@link #load(Path)} with an explicit
     * directory.
     *
     * @return {@link Outcome#ok} with the samples, or {@link Outcome#fail} if the
     *     bundled corpus cannot be located or read
     */
    public Outcome<List<AudioSample>> loadBundled() {
        URL url = CorpusLoader.class.getResource(BUNDLED_CORPUS_RESOURCE);
        if (url == null) {
            return Outcome.fail(
                    "corpus-not-found",
                    "Bundled corpus not found on the classpath at " + BUNDLED_CORPUS_RESOURCE);
        }
        Path corpusDir;
        try {
            corpusDir = Paths.get(url.toURI());
        } catch (URISyntaxException e) {
            return Outcome.fail(
                    "corpus-not-found",
                    "Bundled corpus is not a filesystem directory (%s): %s".formatted(url, e.getMessage()));
        }
        return load(corpusDir);
    }

    /**
     * Loads a corpus from an explicit directory, using the
     * {@value #DEFAULT_LANGUAGE_TAG} language tag.
     *
     * @param corpusDir the corpus root, containing {@code audio/} and
     *     {@code transcripts/} subdirectories
     * @return {@link Outcome#ok} with the samples, or {@link Outcome#fail}
     */
    public Outcome<List<AudioSample>> load(Path corpusDir) {
        return load(corpusDir, DEFAULT_LANGUAGE_TAG);
    }

    /**
     * Loads a corpus from an explicit directory.
     *
     * @param corpusDir   the corpus root, containing {@code audio/} and
     *     {@code transcripts/} subdirectories
     * @param languageTag BCP-47 language tag stamped on every request
     * @return {@link Outcome#ok} with the samples ordered by clip id, or
     *     {@link Outcome#fail} ({@code corpus-missing-audio-dir},
     *     {@code corpus-empty}, {@code corpus-missing-transcript}, or
     *     {@code corpus-read-error})
     */
    public Outcome<List<AudioSample>> load(Path corpusDir, String languageTag) {
        Objects.requireNonNull(corpusDir, "corpusDir");
        Objects.requireNonNull(languageTag, "languageTag");

        Path audioDir = corpusDir.resolve("audio");
        Path transcriptsDir = corpusDir.resolve("transcripts");

        if (!Files.isDirectory(audioDir)) {
            return Outcome.fail(
                    "corpus-missing-audio-dir", "Corpus audio directory not found: " + audioDir);
        }

        List<Path> clips;
        try (Stream<Path> entries = Files.list(audioDir)) {
            clips = entries
                    .filter(Files::isRegularFile)
                    .filter(p -> isClip(p.getFileName().toString()))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            return Outcome.fail(
                    "corpus-read-error",
                    "Could not list corpus audio in %s: %s".formatted(audioDir, e.getMessage()));
        }

        if (clips.isEmpty()) {
            return Outcome.fail("corpus-empty", "No audio clips found under " + audioDir);
        }

        List<AudioSample> samples = new ArrayList<>(clips.size());
        List<String> missing = new ArrayList<>();
        try {
            for (Path clip : clips) {
                String clipId = stemOf(clip.getFileName().toString());
                Path transcript = transcriptsDir.resolve(clipId + ".txt");
                if (!Files.isRegularFile(transcript)) {
                    missing.add(clipId);
                    continue;
                }
                String reference = Files.readString(transcript).strip();
                SttRequest request =
                        new SttRequest(clipId, CLEAN_RECIPE_ID, clip.toAbsolutePath(), languageTag);
                samples.add(new AudioSample(request, reference));
            }
        } catch (IOException e) {
            return Outcome.fail(
                    "corpus-read-error",
                    "Could not read corpus transcripts in %s: %s"
                            .formatted(transcriptsDir, e.getMessage()));
        }

        if (!missing.isEmpty()) {
            return Outcome.fail(
                    "corpus-missing-transcript",
                    "No transcript (transcripts/<clip-id>.txt) for clip(s): " + missing);
        }
        return Outcome.ok(List.copyOf(samples));
    }

    // A corpus clip is any regular file in audio/ that is not the directory
    // README or a hidden/OS metadata file (e.g. .DS_Store). The audio format is
    // not constrained here — providers declare what they accept.
    private static boolean isClip(String fileName) {
        return !fileName.startsWith(".") && !fileName.equalsIgnoreCase("README.md");
    }

    private static String stemOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }
}
