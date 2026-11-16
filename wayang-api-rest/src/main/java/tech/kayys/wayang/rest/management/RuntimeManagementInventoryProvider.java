package tech.kayys.wayang.rest.management;

import tech.kayys.wayang.management.client.ManagementContracts.InventoryRequest;

/** Supplies the authoritative inventory for the active Wayang runtime profile. */
public interface RuntimeManagementInventoryProvider {
    InventoryRequest snapshot();
}
