-- PostgreSQL 16 schema for the optional Wayang knowledge graph.
-- Graph identity is always scoped by tenant_id and collection_id.
CREATE TABLE IF NOT EXISTS wayang_kg_entity (
    tenant_id VARCHAR(200) NOT NULL,
    collection_id VARCHAR(200) NOT NULL,
    entity_id VARCHAR(300) NOT NULL,
    entity_type VARCHAR(120) NOT NULL,
    canonical_name VARCHAR(500) NOT NULL,
    aliases_json TEXT NOT NULL,
    properties_json TEXT NOT NULL,
    PRIMARY KEY (tenant_id, collection_id, entity_id)
);

CREATE TABLE IF NOT EXISTS wayang_kg_relationship (
    tenant_id VARCHAR(200) NOT NULL,
    collection_id VARCHAR(200) NOT NULL,
    relationship_id VARCHAR(300) NOT NULL,
    from_entity_id VARCHAR(300) NOT NULL,
    predicate VARCHAR(120) NOT NULL,
    to_entity_id VARCHAR(300) NOT NULL,
    properties_json TEXT NOT NULL,
    confidence DOUBLE PRECISION NOT NULL,
    PRIMARY KEY (tenant_id, collection_id, relationship_id),
    FOREIGN KEY (tenant_id, collection_id, from_entity_id)
        REFERENCES wayang_kg_entity (tenant_id, collection_id, entity_id),
    FOREIGN KEY (tenant_id, collection_id, to_entity_id)
        REFERENCES wayang_kg_entity (tenant_id, collection_id, entity_id)
);

CREATE INDEX IF NOT EXISTS wayang_kg_relationship_out
    ON wayang_kg_relationship (tenant_id, collection_id, from_entity_id, predicate, relationship_id);
CREATE INDEX IF NOT EXISTS wayang_kg_relationship_in
    ON wayang_kg_relationship (tenant_id, collection_id, to_entity_id, predicate, relationship_id);

CREATE TABLE IF NOT EXISTS wayang_kg_entity_evidence (
    tenant_id VARCHAR(200) NOT NULL,
    collection_id VARCHAR(200) NOT NULL,
    entity_id VARCHAR(300) NOT NULL,
    evidence_id CHAR(64) NOT NULL,
    source_id VARCHAR(200) NOT NULL,
    source_version VARCHAR(200) NOT NULL,
    chunk_id VARCHAR(200) NOT NULL,
    start_offset INTEGER NOT NULL,
    end_offset INTEGER NOT NULL,
    extractor VARCHAR(200) NOT NULL,
    confidence DOUBLE PRECISION NOT NULL,
    PRIMARY KEY (tenant_id, collection_id, entity_id, evidence_id),
    FOREIGN KEY (tenant_id, collection_id, entity_id)
        REFERENCES wayang_kg_entity (tenant_id, collection_id, entity_id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS wayang_kg_entity_evidence_source
    ON wayang_kg_entity_evidence (tenant_id, collection_id, source_id, source_version);

CREATE TABLE IF NOT EXISTS wayang_kg_relationship_evidence (
    tenant_id VARCHAR(200) NOT NULL,
    collection_id VARCHAR(200) NOT NULL,
    relationship_id VARCHAR(300) NOT NULL,
    evidence_id CHAR(64) NOT NULL,
    source_id VARCHAR(200) NOT NULL,
    source_version VARCHAR(200) NOT NULL,
    chunk_id VARCHAR(200) NOT NULL,
    start_offset INTEGER NOT NULL,
    end_offset INTEGER NOT NULL,
    extractor VARCHAR(200) NOT NULL,
    confidence DOUBLE PRECISION NOT NULL,
    PRIMARY KEY (tenant_id, collection_id, relationship_id, evidence_id),
    FOREIGN KEY (tenant_id, collection_id, relationship_id)
        REFERENCES wayang_kg_relationship (tenant_id, collection_id, relationship_id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS wayang_kg_relationship_evidence_source
    ON wayang_kg_relationship_evidence (tenant_id, collection_id, source_id, source_version);
