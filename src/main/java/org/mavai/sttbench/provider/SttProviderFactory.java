package org.mavai.sttbench.provider;

import org.mavai.sttbench.provider.noop.NoopSttProvider;

public class SttProviderFactory {
    public enum ProviderId {
        NOOP,
    }

    public static SttProvider create(ProviderId providerId) {
        return switch (providerId) {
            case NOOP -> new NoopSttProvider();
        };
    }
}
