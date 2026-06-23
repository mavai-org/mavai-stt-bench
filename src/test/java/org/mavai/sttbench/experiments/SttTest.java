package org.mavai.sttbench.experiments;

import org.mavai.punit.api.Experiment;
import org.mavai.punit.runtime.PUnit;
import org.mavai.sttbench.contract.SttServiceContract;
import org.mavai.sttbench.provider.SttProviderFactory;

public class SttTest {
    @Experiment
    void exploreProviders() {
        PUnit.exploring(SttServiceContract.samplingAll())
                .grid(SttProviderFactory.ProviderId.NOOP)
                .experimentId("baseline-v1")
                .run();
    }
}
