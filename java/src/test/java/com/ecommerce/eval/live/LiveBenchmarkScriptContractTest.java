package com.ecommerce.eval.live;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class LiveBenchmarkScriptContractTest {
    @Test
    void paidEntryIsFailClosedAndDoesNotImportEmbeddingCredentials() throws Exception {
        String script = Files.readString(Path.of("scripts", "run-live-agent-benchmark.ps1"));

        int confirmation = script.indexOf("if (-not $ConfirmPaidRun)");
        int gitCheck = script.indexOf("git -C $repo status --porcelain=v1");
        int dotenvRead = script.indexOf("Get-Content -LiteralPath $dotenv");
        int maven = script.indexOf("mvn -B -Plive-agent-benchmark test");
        assertThat(confirmation).isNotNegative().isLessThan(gitCheck);
        assertThat(gitCheck).isLessThan(dotenvRead);
        assertThat(dotenvRead).isLessThan(maven);

        String allowlist = script.substring(script.indexOf("$allowed = @("), dotenvRead);
        assertThat(allowlist)
                .contains("ECOM_LLM_API_KEY", "ECOM_LLM_BASE_URL", "ECOM_LLM_MODEL",
                        "ECOM_RECOMMEND_LLM_PROVIDER")
                .doesNotContain("EMBEDDING", "QWEN", "DASHSCOPE");
        assertThat(script)
                .contains("Git worktree must be clean.")
                .contains("Unable to verify Git worktree state.")
                .contains("IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name, 'Process'))")
                .contains("throw \"Live benchmark Maven execution failed (exit code $LASTEXITCODE).\"")
                .contains("Pop-Location")
                .contains("[Environment]::SetEnvironmentVariable($name, $previousEnvironment[$name], 'Process')")
                .contains("[Environment]::SetEnvironmentVariable($name, $previousAllowedEnvironment[$name], 'Process')");
    }
}
