package tech.kayys.wayang.api.oauth;

import org.junit.jupiter.api.Test;

import tech.kayys.wayang.api.testkit.OAuthStoreContract;

class OAuthStoreTest {

    @Test
    void inMemoryStoreSatisfiesTheContract() {
        OAuthStoreContract.verify(OAuthStore.InMemory::new);
    }
}
