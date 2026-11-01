package tech.kayys.wayang.runtime;

import org.junit.jupiter.api.Test;

import tech.kayys.wayang.runtime.testkit.WaitRegistryContract;

class WaitRegistryContractTest {
    @Test
    void inMemoryRegistrySatisfiesTheContract() {
        WaitRegistryContract.verify(WaitRegistry.InMemory::new);
    }
}
