# Creative Content Mode Architecture (Future)

> Exploratory target only. Not a launch commitment and not a dependency of the initial Wayang modes.

## 1. Scope

A future mode inspired by node-based creative systems may author image, audio, video, animation, 3D or multimodal generation pipelines. It requires media-specific graph semantics, asset lineage, previews, model management and GPU-aware execution. Do not treat a general workflow graph as a complete media graph.

## 2. Future logical architecture

```mermaid
flowchart TB
  subgraph Studio["Creative Studio - future"]
    Graph[Media graph editor]
    Preview[Preview / compare / timeline]
    Models[Model and sampler catalog]
    Assets[Asset browser and lineage]
  end
  subgraph Project["Creative project"]
    GraphDoc[Typed media graph]
    Params[Model, prompt and parameter versions]
    Inputs[Input assets and masks]
    Outputs[Output and provenance references]
  end
  subgraph Control["Media execution control"]
    Planner[Graph planner]
    Scheduler[GPU/resource scheduler]
    Queue[Bounded work queue]
    Cache[Model residency / cache manager]
  end
  subgraph Workers["Specialized workers"]
    Image[Image generation workers]
    Video[Video workers]
    Audio[Audio workers]
    Transform[Decode / encode / upscale workers]
  end
  subgraph Storage["Asset and model storage"]
    Object[(Object storage)]
    ModelStore[(Model registry / weights)]
    Metadata[(Asset metadata and lineage)]
  end
  Graph --> GraphDoc
  Preview --> Outputs
  Models --> Params
  Assets --> Inputs
  GraphDoc --> Planner
  Params --> Planner
  Inputs --> Planner
  Planner --> Queue
  Queue --> Scheduler
  Scheduler --> Image
  Scheduler --> Video
  Scheduler --> Audio
  Scheduler --> Transform
  Cache --> Workers
  ModelStore --> Cache
  Workers --> Object
  Object --> Metadata
  Metadata --> Assets
```

## 3. Media graph execution sequence

```mermaid
sequenceDiagram
  autonumber
  actor Creator
  participant Studio as Creative Studio
  participant Planner as Graph Planner
  participant Queue as Work Queue
  participant Scheduler as GPU Scheduler
  participant Worker as Media Worker
  participant Assets as Asset Store
  Creator->>Studio: Author graph and choose model/parameters
  Studio->>Planner: Validate graph, types, assets and model compatibility
  Planner-->>Studio: Execution plan and resource estimate
  Creator->>Studio: Submit generation
  Studio->>Queue: Create job with graph/version/seed
  Queue->>Scheduler: Request compatible worker resources
  Scheduler->>Worker: Assign GPU/model/resource lease
  Worker->>Assets: Load input assets and model weights
  Worker->>Worker: Execute nodes and persist intermediate checkpoints
  Worker->>Assets: Store output assets and lineage
  Worker-->>Queue: Result, metrics and artifact references
  Queue-->>Studio: Job state and preview references
```

## 4. Resource scheduling and scaling

Workers advertise capabilities such as operation type, model availability, VRAM class, precision, accelerator type, supported codecs and concurrency. Placement must account for model download/startup cost, GPU memory, batching, locality to assets, fairness and tenant quotas.

Use bounded queues, cancellation, resource leases, preemption policy (if supported), checkpoint/resume and cleanup of temporary assets. Scale workers by queue age, GPU utilization, VRAM pressure and job class, not CPU alone. Large assets should move by object reference or streaming protocol, not through the control plane.

## 5. Asset lifecycle and provenance

Each output should retain parent asset IDs, graph and node versions, model identifier/version/digest, prompt/parameter references, seed where applicable, execution environment, timestamps, license/provenance metadata and transformation lineage. Keep binary media in object storage; store metadata and references in project/runtime state.

## 6. Reuse and separation

Reuse Wayang's project, capability-pack, schema, artifact and deployment contracts. Keep media graph model, preview/timeline UI, model residency, GPU scheduling and streaming media transport in the future Creative pack and specialized worker services. Do not add GPU scheduling concepts to neutral core or BPMN/DMN semantics.
