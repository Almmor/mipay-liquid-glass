package com.liquidglass.mipay;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Root 辅助工具。
 *
 * <p>为「一键重启目标应用」提供能力：通过 {@code su -c} 以 root 身份强制停止目标进程，
 * 再由本应用重新拉起它，从而让 LSPosed 在目标进程重新加载模块与新配置。</p>
 *
 * <p>本类只执行 <b>停止 / 查询</b> 这两类动作，不涉及任何文件写入或系统属性修改。</p>
 */
public final class RootHelper {

    /** 小米智能卡。 */
    public static final String PKG_SMART_CARD = "com.miui.tsmclient";
    /** 小米钱包。 */
    public static final String PKG_MIPAY_WALLET = "com.mipay.wallet";

    /** 命令执行结果。 */
    public static final class Result {
        public final boolean success;
        public final String output;

        Result(boolean success, String output) {
            this.success = success;
            this.output = output;
        }
    }

    private RootHelper() {
    }

    /**
     * 以 root 身份执行一条命令。
     *
     * <p>使用 {@code ProcessBuilder("su", "-c", command)} 而不是拼 shell 字符串，
     * 避免不同 su 实现对参数转义处理的差异。整个过程在调用线程同步执行，
     * 请在子线程中调用。</p>
     */
    public static Result exec(String command) {
        Process process = null;
        try {
            process = new ProcessBuilder("su", "-c", command)
                    .redirectErrorStream(true)
                    .start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }
            int exitCode = process.waitFor();
            return new Result(exitCode == 0, output.toString().trim());
        } catch (Throwable throwable) {
            return new Result(false, String.valueOf(throwable.getMessage()));
        } finally {
            if (process != null) {
                try {
                    process.destroy();
                } catch (Throwable ignored) {
                    // ignore
                }
            }
        }
    }

    /** 是否已经拿到 root（执行 id 并检查 uid=0）。会触发系统授权弹窗。 */
    public static boolean isRootAvailable() {
        Result result = exec("id");
        return result.success && result.output.contains("uid=0");
    }

    /** 强制停止指定包名的所有进程。 */
    public static Result forceStop(String packageName) {
        return exec("am force-stop " + packageName);
    }

    /**
     * 强制停止目标进程。失败时回退到 {@code pkill}。
     *
     * <p>部分定制 ROM 收紧了对 {@code am force-stop} 的限制，
     * 此时直接用信号终止进程同样能达到「让 LSPosed 重新注入」的目的。</p>
     */
    public static Result forceStopWithFallback(String packageName) {
        Result result = forceStop(packageName);
        if (result.success) {
            return result;
        }
        Result fallback = exec("pkill -f " + packageName);
        return fallback.success ? fallback : result;
    }
}
