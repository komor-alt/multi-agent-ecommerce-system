package com.ecommerce.eval.live;

import com.ecommerce.eval.benchmark.AgentBenchmarkDataset;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LiveBenchmarkSmokeEvidenceTest {
    private byte[] reportBytes;
    private AgentBenchmarkDataset.Loaded dataset;
    private List<AgentBenchmarkDataset.Case> smokeCases;

    @BeforeEach
    void loadPinnedEvidenceFixture() throws Exception {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(
                LiveBenchmarkSmokeEvidence.TEST_REPORT_RESOURCE)) {
            assertThat(stream).isNotNull();
            reportBytes = stream.readAllBytes();
        }
        dataset = AgentBenchmarkDataset.loadAll();
        smokeCases = AgentBenchmarkDataset.selectSmoke(dataset.cases());
    }

    @Test
    void acceptsOnlyThePinnedCompletedDeepSeekCoreSmokeAndInfrastructureDescendants() {
        var evidence = LiveBenchmarkSmokeEvidence.verifyBytes(reportBytes,
                LiveBenchmarkSmokeEvidence.PINNED_REPORT_SHA256,
                "b".repeat(40),
                List.of("java/src/test/java/com/ecommerce/eval/live/LiveBenchmarkFixtureSeeder.java"),
                true, dataset, smokeCases);

        assertThat(evidence.valid()).isTrue();
    }

    @Test
    void acceptsEquivalentLfAndCrlfReportBytesUsingThePinnedCanonicalDigest() throws Exception {
        String report = new String(reportBytes, StandardCharsets.UTF_8);
        String lfReport = report.replace("\r\n", "\n").replace('\r', '\n');
        byte[] lfBytes = lfReport.getBytes(StandardCharsets.UTF_8);
        byte[] crlfBytes = lfReport.replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8);

        assertThat(LiveBenchmarkSmokeEvidence.canonicalReportSha256(lfBytes))
                .isEqualTo(LiveBenchmarkSmokeEvidence.PINNED_REPORT_SHA256);
        assertThat(LiveBenchmarkSmokeEvidence.canonicalReportSha256(crlfBytes))
                .isEqualTo(LiveBenchmarkSmokeEvidence.PINNED_REPORT_SHA256);
        assertThat(verify(lfBytes, LiveBenchmarkSmokeEvidence.PINNED_REPORT_SHA256,
                "b".repeat(40), List.of(), true, dataset).valid()).isTrue();
        assertThat(verify(crlfBytes, LiveBenchmarkSmokeEvidence.PINNED_REPORT_SHA256,
                "b".repeat(40), List.of(), true, dataset).valid()).isTrue();
    }

    @Test
    void powershellRunnerUsesTheSameCanonicalEvidenceHashRule() throws Exception {
        String script = Files.readString(Path.of("scripts", "run-live-agent-benchmark.ps1"));

        assertThat(script)
                .contains("function Get-CanonicalEvidenceSha256")
                .contains("[System.Text.UTF8Encoding]::new($false, $true)")
                .contains("[regex]::Replace($text, \"\\r\\n|\\r|\\n\", \"`r`n\")")
                .contains("$actualReportSha256 = Get-CanonicalEvidenceSha256 -Path $resolvedReport")
                .doesNotContain("Get-FileHash -LiteralPath $resolvedReport");
    }

    @Test
    void rejectsReportTamperingAndWrongSuppliedDigest() {
        byte[] tampered = reportBytes.clone();
        tampered[tampered.length / 2] ^= 1;

        assertThatThrownBy(() -> verify(tampered, LiveBenchmarkSmokeEvidence.PINNED_REPORT_SHA256,
                "b".repeat(40), List.of(), true, dataset))
                .hasMessageContaining("SHA-256 mismatch");
        assertThatThrownBy(() -> verify(reportBytes, "0".repeat(64),
                "b".repeat(40), List.of(), true, dataset))
                .hasMessageContaining("SHA-256 mismatch");
    }

    @Test
    void rejectsDatasetSelectionAndBaseCommitMismatch() {
        AgentBenchmarkDataset.Loaded otherDataset = new AgentBenchmarkDataset.Loaded(
                dataset.cases(), "e".repeat(64), Map.of("regression", "f".repeat(64)),
                dataset.faultPlanHash(), dataset.references());
        assertThatThrownBy(() -> verify(reportBytes, LiveBenchmarkSmokeEvidence.PINNED_REPORT_SHA256,
                "b".repeat(40), List.of(), true, otherDataset))
                .hasMessageContaining("datasetHash");

        assertThat(verify(reportBytes, LiveBenchmarkSmokeEvidence.PINNED_REPORT_SHA256,
                "c".repeat(40), List.of(), true, dataset).valid()).isTrue();
        assertThatThrownBy(() -> verify(reportBytes, LiveBenchmarkSmokeEvidence.PINNED_REPORT_SHA256,
                "b".repeat(40), List.of(), false, dataset))
                .hasMessageContaining("not a concrete descendant");
    }

    @Test
    void acceptsInfrastructureDescendantButRejectsSmokeProducedByTheWrongCommit() throws Exception {
        var report = new ObjectMapper().readTree(reportBytes);
        assertThatCode(() -> LiveBenchmarkSmokeEvidence.validateReport(report, dataset, smokeCases))
                .doesNotThrowAnyException();
        ((com.fasterxml.jackson.databind.node.ObjectNode) report).put("javaRevision", "c".repeat(40));
        assertThatThrownBy(() -> LiveBenchmarkSmokeEvidence.validateReport(report, dataset, smokeCases))
                .hasMessageContaining("javaRevision");
    }

    @Test
    void rejectsUnrelatedChangesEvenWhenCommitClaimsToDescendFromBaseline() {
        assertThatThrownBy(() -> verify(reportBytes, LiveBenchmarkSmokeEvidence.PINNED_REPORT_SHA256,
                "b".repeat(40), List.of("java/src/main/java/com/ecommerce/service/AutonomousAgentLoopService.java"),
                true, dataset))
                .hasMessageContaining("non-allowlisted changes");
        assertThatThrownBy(() -> verify(reportBytes, LiveBenchmarkSmokeEvidence.PINNED_REPORT_SHA256,
                "b".repeat(40), List.of("java/src/test/resources/recommendation-agent-complex-business.jsonl"),
                true, dataset))
                .hasMessageContaining("non-allowlisted changes");
    }

    @Test
    void manualSmokePassedEnvironmentVariableCannotReplaceReportEvidence() {
        Map<String, String> env = new HashMap<>();
        env.put(LiveAgentBenchmarkGuard.RUN_ENV, "true");
        env.put(LiveAgentBenchmarkGuard.SCOPE_ENV, "PILOT");
        env.put("ECOM_AGENT_BENCHMARK_SMOKE_PASSED", "true");

        assertThatThrownBy(() -> LiveAgentBenchmarkGuard.requireConfiguration(env, 102, 9, 3, 2, null))
                .hasMessageContaining("verified pinned historical SMOKE report evidence");
        assertThatThrownBy(() -> LiveBenchmarkSmokeEvidence.verifyForRun(env,
                java.nio.file.Path.of("."), dataset, smokeCases))
                .hasMessageContaining("explicit historical SMOKE report path and SHA-256");
    }

    private LiveBenchmarkSmokeEvidence.Verified verify(byte[] bytes, String hash, String commit,
                                                         List<String> changes, boolean ancestor,
                                                         AgentBenchmarkDataset.Loaded selectedDataset) {
        return LiveBenchmarkSmokeEvidence.verifyBytes(bytes, hash, commit, changes, ancestor,
                selectedDataset, smokeCases);
    }
}
