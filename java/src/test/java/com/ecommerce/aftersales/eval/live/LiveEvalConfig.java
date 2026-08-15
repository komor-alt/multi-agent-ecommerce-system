package com.ecommerce.aftersales.eval.live;

import java.util.Map;

/**
 * Live LLM eval 环境配置校验（纯 Java，无框架依赖；可注入 env 便于离线单元测试）。
 *
 * 语义（与 README「Optional Live LLM Eval」一致）：
 * - ECOM_RUN_LIVE_EVAL=true 才运行；未开启时测试类在 JUnit 条件阶段直接跳过
 *   （@EnabledIfEnvironmentVariable），不启动 Spring 上下文、零网络/模型调用；
 * - 开启后 ECOM_LLM_API_KEY 必须为真实 key：缺失或占位值（your_api_key_here）
 *   必须清晰失败，绝不静默降级到规则（否则就不是 Live Eval）。
 */
public final class LiveEvalConfig {

    public static final String RUN_ENV = "ECOM_RUN_LIVE_EVAL";
    public static final String API_KEY_ENV = "ECOM_LLM_API_KEY";
    public static final String BASE_URL_ENV = "ECOM_LLM_BASE_URL";
    public static final String MODEL_ENV = "ECOM_LLM_MODEL";
    public static final String MAX_CALLS_ENV = "ECOM_LLM_MAX_CALLS";
    /** 与 application.yml 的 ${ECOM_LLM_API_KEY:your_api_key_here} 占位值一致。 */
    public static final String PLACEHOLDER_API_KEY = "your_api_key_here";

    private LiveEvalConfig() {
    }

    /** 系统环境视角：直接读 System.getenv。 */
    public static boolean runEnabled() {
        return runEnabled(System.getenv());
    }

    /**
     * 可注入 env（Map<String,String>）：值 trim 后严格等于 "true"（忽略大小写，TRUE/True 均可）
     * 才开启。整词匹配（"truex" 不算）——与 LiveAfterSalesEvalRunnerTest 的
     * {@code @EnabledIfEnvironmentVariable(matches = "(?i)true")} 语义完全一致；
     * 注意 env 值若带首尾空白，runEnabled 会 trim 后判定为开启，而 JUnit 注解按原值不匹配
     * （注解无法 trim；实际环境变量不会带空白，属已知边界）。
     */
    public static boolean runEnabled(Map<String, String> env) {
        return "true".equalsIgnoreCase(trimmed(env, RUN_ENV));
    }

    /**
     * 校验真实 API key：缺失、空白或等于占位值 → 抛出带明确修复指引的异常
     * （Live Eval 开启时不允许静默回退规则）。返回规范化后的 key。
     */
    public static String requireApiKey() {
        return requireApiKey(System.getenv());
    }

    /** 可注入 env 版本的 requireApiKey。 */
    public static String requireApiKey(Map<String, String> env) {
        String key = trimmed(env, API_KEY_ENV);
        if (key.isEmpty() || PLACEHOLDER_API_KEY.equals(key)) {
            throw new IllegalStateException(
                    "LIVE_EVAL_CONFIG_FAILURE: " + RUN_ENV + "=true but " + API_KEY_ENV
                            + " is missing or set to the placeholder '" + PLACEHOLDER_API_KEY + "'. "
                            + "Live LLM eval requires a real API key and must never silently fall back to rules. "
                            + "Set " + API_KEY_ENV + "=<real-key> and re-run "
                            + "(see README section 'Optional Live LLM Eval').");
        }
        return key;
    }

    /** Live Eval requires an explicit positive global model-call budget. */
    public static int requireMaxLlmCalls() {
        return requireMaxLlmCalls(System.getenv());
    }

    public static int requireMaxLlmCalls(Map<String, String> env) {
        String raw = trimmed(env, MAX_CALLS_ENV);
        try {
            int value = Integer.parseInt(raw);
            if (value <= 0) {
                throw new NumberFormatException("not positive");
            }
            return value;
        } catch (NumberFormatException error) {
            throw new IllegalStateException(
                    "LIVE_EVAL_CONFIG_FAILURE: " + RUN_ENV + "=true requires "
                            + MAX_CALLS_ENV + " to be a positive integer. "
                            + "No live model call is allowed without an explicit budget.");
        }
    }
    /** 空实现：静态工具类不可实例化之外的守卫（供反射/序列化框架避免误用）。 */
    private static String trimmed(Map<String, String> env, String name) {
        String value = env.get(name);
        return value == null ? "" : value.trim();
    }
}
