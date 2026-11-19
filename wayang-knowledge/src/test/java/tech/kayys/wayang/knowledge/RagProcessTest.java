package tech.kayys.wayang.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import tech.kayys.wayang.bpmn.ElementType;
import tech.kayys.wayang.bpmn.ProcessCompiler;
import tech.kayys.wayang.bpmn.ProcessModel;
import tech.kayys.wayang.runtime.ControlHandler;
import tech.kayys.wayang.runtime.NodeRunner;
import tech.kayys.wayang.runtime.ReferenceEngine;
import tech.kayys.wayang.runtime.SecretResolver;
import tech.kayys.wayang.runtime.ServiceHandler;
import tech.kayys.wayang.runtime.WaitRegistry;

/** The worked example of docs/RAG.md, run for real: a process ingests the uploaded documents, then answers a question from them. */
class RagProcessTest {

    private static ReferenceEngine engine() throws Exception {
        KnowledgeExtension ext = new KnowledgeExtension(new FileStore(java.nio.file.Files.createTempDirectory("wayang-files")));
        return new ReferenceEngine(new NodeRunner(List.of(new ControlHandler(new WaitRegistry.InMemory()), new ServiceHandler(ext.connectors()))),
                new WaitRegistry.InMemory());
    }

    private static ProcessModel process() {
        return ProcessModel.builder("research").start("s")
                .task("learn", ElementType.SERVICE_TASK, Map.of("connector", "rag-ingest", "knowledge", "contracts",
                        "chunking", Map.of("type", "clause", "maxChars", 1200),
                        "inputs", Map.of("documents", "documents")))
                .task("lookup", ElementType.SERVICE_TASK, Map.of("connector", "rag-search", "knowledge", "contracts",
                        "retrieval", Map.of("method", "bm25", "topK", 3, "mmr", true),
                        "inputs", Map.of("query", "question")))
                .end("e").flow("s", "learn").flow("learn", "lookup").flow("lookup", "e").build();
    }

    @Test
    void ingestThenAnswerFromTheDocuments() throws Exception {
        var run = engine().start(ProcessCompiler.compile(process()), Map.of(
                SecretResolver.TENANT_VARIABLE, "acme",
                "question", "termination notice period",
                "documents", List.of(
                        Map.of("fileName", "msa.md", "text", "Article 1 Scope\nServices as described.\n\nArticle 2 Termination\nEither party may terminate with 30 days written notice.\n\nArticle 3 Fees\nFees are net 45."),
                        Map.of("fileName", "faq.txt", "text", "Lunch is served at noon.", "metadata", Map.of("kind", "other")))));
        assertEquals(ReferenceEngine.Status.COMPLETED, run.status(), String.valueOf(run.error()));
        assertEquals(2, run.variables().get("learn.ingested"));
        assertTrue(String.valueOf(run.variables().get("lookup.context")).contains("30 days written notice"), String.valueOf(run.variables().get("lookup.context")));
        assertTrue(String.valueOf(run.variables().get("lookup.context")).contains("msa.md"));
    }

    @Test
    void anotherTenantRunningTheSameProcessSeesNothing() throws Exception {
        ReferenceEngine e = engine();
        e.start(ProcessCompiler.compile(process()), Map.of(SecretResolver.TENANT_VARIABLE, "acme", "question", "x",
                "documents", List.of(Map.of("fileName", "msa.md", "text", "Termination with 30 days notice."))));
        var other = e.start(ProcessCompiler.compile(ProcessModel.builder("q").start("s")
                .task("lookup", ElementType.SERVICE_TASK, Map.of("connector", "rag-search", "knowledge", "contracts", "inputs", Map.of("query", "question")))
                .end("e").flow("s", "lookup").flow("lookup", "e").build()), Map.of(SecretResolver.TENANT_VARIABLE, "globex", "question", "termination"));
        assertEquals(ReferenceEngine.Status.FAILED, other.status());
        assertTrue(String.valueOf(other.error()).contains("is empty"), String.valueOf(other.error()));
    }

    @Test
    void storeAFileOnceThenFeedItToKnowledgeById() throws Exception {
        ProcessModel m = ProcessModel.builder("filed").start("s")
                .task("put", ElementType.SERVICE_TASK, Map.of("connector", "file-put", "inputs", Map.of("fileName", "fileName", "text", "body")))
                .task("learn", ElementType.SERVICE_TASK, Map.of("connector", "rag-ingest", "knowledge", "filed",
                        "inputMapping", Map.of("documents", List.of(Map.of("fileId", "put.fileId")))))
                .task("lookup", ElementType.SERVICE_TASK, Map.of("connector", "rag-search", "knowledge", "filed", "inputs", Map.of("query", "question")))
                .end("e").flow("s", "put").flow("put", "learn").flow("learn", "lookup").flow("lookup", "e").build();
        var run = engine().start(ProcessCompiler.compile(m), Map.of(SecretResolver.TENANT_VARIABLE, "acme", "fileName", "policy.md",
                "body", "# Policy\n\nRefunds are issued within five business days.", "question", "refund days"));
        assertEquals(ReferenceEngine.Status.COMPLETED, run.status(), String.valueOf(run.error()));
        assertEquals(true, run.variables().get("put.stored"));
        assertTrue(String.valueOf(run.variables().get("lookup.context")).contains("five business days"), String.valueOf(run.variables().get("lookup.context")));
    }

    @Test
    void aMissingMandatoryInputWarnsButTheWorkflowGoesOn() throws Exception {
        ProcessModel m = ProcessModel.builder("lenient").start("s")
                .task("learn", ElementType.SERVICE_TASK, Map.of("connector", "rag-ingest", "knowledge", "lenient", "inputs", Map.of("documents", "documents")))
                .task("lookup", ElementType.SERVICE_TASK, Map.of("connector", "rag-search", "knowledge", "lenient", "inputs", Map.of("query", "question")))
                .end("e").flow("s", "learn").flow("learn", "lookup").flow("lookup", "e").build();
        var run = engine().start(ProcessCompiler.compile(m), Map.of(SecretResolver.TENANT_VARIABLE, "acme"));
        assertEquals(ReferenceEngine.Status.COMPLETED, run.status(), String.valueOf(run.error()));
        assertEquals(0, run.variables().get("learn.ingested"));
        assertTrue(String.valueOf(run.variables().get("learn.warnings")).contains("documents"), String.valueOf(run.variables()));
        assertEquals(0, run.variables().get("lookup.count"));
        assertTrue(String.valueOf(run.variables().get("lookup.warnings")).contains("query"));
    }
}
