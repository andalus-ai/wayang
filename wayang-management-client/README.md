# Wayang Runtime Management Client

This module is the Wayang runtime-side REST/JSON adapter for the management API implemented by Wayang Console.

## Scope

- Implements the five Console management operations: enrollment, heartbeat, inventory replacement, command polling and acknowledgement.
- Uses the one-time enrollment token only in the enrollment request body.
- Uses the issued registration bearer token for all subsequent requests.
- Maps the Console's current JSON records, including `typedPayload` as a JSON string and `verifiedArtifactDigest` on acknowledgements.
- Exposes HTTP status/error codes and a conservative retry classification to the host runtime.

## Deliberate non-goals

- It does not persist registration credentials. The embedding runtime must provide a secure credential store and must not log the bearer token.
- It does not retry enrollment. Enrollment consumes a one-time credential, so blindly retrying after an ambiguous network failure could consume or strand that credential.
- It does not schedule heartbeats or polling. Lifecycle, bounded backoff, credential rotation and shutdown belong to the host integration.
- It does not execute `APPLY_DEPLOYMENT` commands. A separate runtime command-handler SPI must validate and execute deployment payloads, report `STARTED`, and acknowledge terminal results with the verified artifact digest.
- It does not use the protobuf schema as a substitute for the live REST contract.

## Usage

```java
var client = new ManagementClient(
        URI.create("https://console.example.com/"),
        Duration.ofSeconds(5),
        Duration.ofSeconds(10));

var registration = client.enroll(new EnrollRequest(
        enrollmentToken, instanceId, runtimeVersion, deploymentId, hostname, platform));

// Persist registration.registrationToken() using the host's secure credential store.
// Do not log the registration response or token.
client.heartbeat(registration.registrationToken(), "HEALTHY");
client.reportInventory(registration.registrationToken(), inventory);
var commands = client.pollCommands(registration.registrationToken(), 10);
```

The base URI may include a path prefix; the client appends `api/v1/runtime/`. It accepts only absolute HTTP(S) URIs without user-info, query or fragment. Configure TLS at the endpoint and do not send credentials over plaintext HTTP outside isolated tests.

## Validation

`ManagementClientTest` uses an in-process HTTP server to verify request/response mapping for all five operations, the enrollment-versus-bearer authentication boundary, poll bounds, and credential error classification. These tests are contract-adapter tests; they do not yet prove a live Console/Wayang deployment.
