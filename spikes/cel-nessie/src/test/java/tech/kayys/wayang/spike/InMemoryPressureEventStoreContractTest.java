package tech.kayys.wayang.spike;

/** Runs the reusable store contract against the volatile reference implementation. */
class InMemoryPressureEventStoreContractTest extends PressureEventStoreContract {
    @Override
    protected PressureEventStore newStore() {
        return new InMemoryPressureEventStore();
    }
}
