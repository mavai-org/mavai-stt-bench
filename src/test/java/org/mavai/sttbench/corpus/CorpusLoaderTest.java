package org.mavai.sttbench.corpus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mavai.outcome.Outcome;
import org.mavai.sttbench.contract.AudioSample;

class CorpusLoaderTest {

    private final CorpusLoader loader = new CorpusLoader();

    // ── The bundled corpus shipped on the classpath ──────────────────────────

    @Test
    void loadsBundledCorpusPairingEachClipWithItsTranscript() {
        Outcome<List<AudioSample>> outcome = loader.loadBundled();

        assertThat(outcome.isOk()).as("bundled corpus should load: %s", outcome).isTrue();
        List<AudioSample> samples = outcome.getOrThrow();

        assertThat(samples).isNotEmpty();
        assertThat(samples).allSatisfy(sample -> {
            assertThat(sample.request().clipId()).isNotBlank();
            assertThat(sample.request().recipeId()).isEqualTo(CorpusLoader.CLEAN_RECIPE_ID);
            assertThat(sample.request().languageTag()).isEqualTo(CorpusLoader.DEFAULT_LANGUAGE_TAG);
            assertThat(sample.request().audioPath()).exists();
            assertThat(sample.reference()).isNotBlank();
        });
    }

    @Test
    void bundledClipsAreOrderedByIdAndCarryTheirReferenceText() {
        List<AudioSample> samples = loader.loadBundled().getOrThrow();

        List<String> clipIds = samples.stream().map(s -> s.request().clipId()).toList();
        assertThat(clipIds).isSorted().doesNotHaveDuplicates();

        AudioSample first = samples.get(0);
        assertThat(first.request().clipId()).isEqualTo("aeon_001");
        assertThat(first.reference()).isEqualTo("Hello and welcome to the AEON world.");
    }

    // ── Happy path over a synthetic corpus ───────────────────────────────────

    @Test
    void pairsClipsByStemAndStripsSurroundingWhitespaceFromReferences(@TempDir Path tmp)
            throws IOException {
        Path corpus = tmp.resolve("corpus");
        writeFile(corpus.resolve("audio/clip_a.wav"), "fake-audio-a");
        writeFile(corpus.resolve("audio/clip_b.wav"), "fake-audio-b");
        writeFile(corpus.resolve("transcripts/clip_a.txt"), "  the quick brown fox \n");
        writeFile(corpus.resolve("transcripts/clip_b.txt"), "jumps over the lazy dog\n");

        List<AudioSample> samples = loader.load(corpus, "en-GB").getOrThrow();

        assertThat(samples).extracting(s -> s.request().clipId()).containsExactly("clip_a", "clip_b");
        assertThat(samples).extracting(AudioSample::reference)
                .containsExactly("the quick brown fox", "jumps over the lazy dog");
        assertThat(samples).allSatisfy(
                s -> assertThat(s.request().languageTag()).isEqualTo("en-GB"));
        assertThat(samples.get(0).request().audioPath()).isAbsolute().exists();
    }

    @Test
    void readmeAndHiddenFilesInTheAudioDirectoryAreNotTreatedAsClips(@TempDir Path tmp)
            throws IOException {
        Path corpus = tmp.resolve("corpus");
        writeFile(corpus.resolve("audio/clip_a.wav"), "fake-audio");
        writeFile(corpus.resolve("audio/README.md"), "# not a clip");
        writeFile(corpus.resolve("audio/.hidden"), "hidden file, e.g. OS metadata");
        writeFile(corpus.resolve("transcripts/clip_a.txt"), "only one real clip");

        List<AudioSample> samples = loader.load(corpus).getOrThrow();

        assertThat(samples).extracting(s -> s.request().clipId()).containsExactly("clip_a");
    }

    // ── Expected failures travel as data, not exceptions ─────────────────────

    @Test
    void aMissingAudioDirectoryIsAnExpectedFailure(@TempDir Path tmp) {
        Outcome<List<AudioSample>> outcome = loader.load(tmp.resolve("corpus"));

        assertThat(outcome.isFail()).isTrue();
        assertThat(failureMessage(outcome)).contains("audio");
    }

    @Test
    void anAudioDirectoryWithNoClipsIsAnExpectedFailure(@TempDir Path tmp) throws IOException {
        Path corpus = tmp.resolve("corpus");
        Files.createDirectories(corpus.resolve("audio"));

        Outcome<List<AudioSample>> outcome = loader.load(corpus);

        assertThat(outcome.isFail()).isTrue();
    }

    @Test
    void aClipWithNoTranscriptIsAnExpectedFailureNamingTheClip(@TempDir Path tmp)
            throws IOException {
        Path corpus = tmp.resolve("corpus");
        writeFile(corpus.resolve("audio/clip_a.wav"), "fake-audio");
        writeFile(corpus.resolve("audio/orphan.wav"), "fake-audio");
        writeFile(corpus.resolve("transcripts/clip_a.txt"), "has a transcript");

        Outcome<List<AudioSample>> outcome = loader.load(corpus);

        assertThat(outcome.isFail()).isTrue();
        assertThat(failureMessage(outcome)).contains("orphan");
    }

    // ── Defects (programming errors) still throw ─────────────────────────────

    @Test
    void aNullCorpusDirectoryIsADefectThatThrows() {
        assertThatNullPointerException()
                .isThrownBy(() -> loader.load(null))
                .withMessageContaining("corpusDir");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static void writeFile(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    private static String failureMessage(Outcome<?> outcome) {
        assertThat(outcome).isInstanceOf(Outcome.Fail.class);
        return ((Outcome.Fail<?>) outcome).failure().message();
    }
}
