package com.liquidglass.mipay;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

/**
 * 模块设置界面。
 *
 * <p>使用 Framework 原生控件实现，不引入 AndroidX，因此 APK 体积很小，
 * 也不会与系统自带的 Material 主题冲突。界面本身也采用了玻璃质感的设计语言，
 * 用于预览材质效果。</p>
 *
 * <p>除参数配置外，这里还提供「一键重启小米智能卡」：通过 Root 强制停止目标进程，
 * 再由本应用重新拉起，使 LSPosed 立即以新配置重新注入。</p>
 */
public class SettingsActivity extends Activity {

    private SharedPreferences prefs;
    private TextView rootStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        prefs = getSharedPreferences(Config.PREFS_NAME, MODE_PRIVATE);

        bindSwitch(R.id.sw_enable, Config.KEY_ENABLE, true);
        bindSwitch(R.id.sw_refraction, Config.KEY_REFRACTION, true);
        bindSwitch(R.id.sw_flow, Config.KEY_FLOW, false);
        bindSwitch(R.id.sw_deep, Config.KEY_DEEP, false);
        bindSwitch(R.id.sw_immersive, Config.KEY_IMMERSIVE, false);
        bindSwitch(R.id.sw_keep_face, Config.KEY_KEEP_CARD_FACE, true);
        bindSwitch(R.id.sw_smart_targets, Config.KEY_SMART_TARGETS, true);
        bindSwitch(R.id.sw_overlay, Config.KEY_GLASS_OVERLAY, true);
        bindSwitch(R.id.sw_rim_stroke, Config.KEY_RIM_STROKE, false);

        bindSeekBar(R.id.seek_blur, R.id.tv_blur_value, Config.KEY_BLUR, 32, true);
        bindSeekBar(R.id.seek_tint, R.id.tv_tint_value, Config.KEY_TINT, 64, false);
        bindSeekBar(R.id.seek_corner, R.id.tv_corner_value, Config.KEY_CORNER, 26, true);
        bindMmSeekBar(R.id.seek_card_radius, R.id.tv_card_radius_value,
                Config.KEY_CARD_RADIUS_MM, 3.18f);

        rootStatus = findViewById(R.id.tv_root_status);
        bindRootActions();

        updateStatus();
    }

    // ======================================================================
    // 配置项
    // ======================================================================

    private void bindSwitch(int viewId, final String key, boolean defaultValue) {
        Switch toggle = findViewById(viewId);
        if (toggle == null) {
            return;
        }
        toggle.setChecked(prefs.getBoolean(key, defaultValue));
        toggle.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean(key, isChecked).apply();
            updateStatus();
        });
    }

    private void bindSeekBar(int seekBarId, int labelId, final String key,
                             int defaultValue, final boolean isDp) {
        final SeekBar seekBar = findViewById(seekBarId);
        final TextView label = findViewById(labelId);
        if (seekBar == null || label == null) {
            return;
        }

        int value = prefs.getInt(key, defaultValue);
        seekBar.setProgress(value);
        label.setText(formatValue(value, isDp));

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                label.setText(formatValue(progress, isDp));
                if (fromUser) {
                    prefs.edit().putInt(key, progress).apply();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                // no-op
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                // no-op
            }
        });
        prefs.edit().putInt(key, value).apply();
    }

    private String formatValue(int value, boolean isDp) {
        return getString(isDp ? R.string.unit_dp : R.string.unit_alpha, value);
    }

    /**
     * 以「毫米」为单位的滑杆。
     *
     * <p>SeekBar 只支持整数进度，因此进度按 1/100 毫米存储：进度 318 = 3.18mm。
     * 卡面圆角这类物理规格用毫米表达，才能在不同屏幕密度下保持一致。</p>
     */
    private void bindMmSeekBar(int seekBarId, int labelId, final String key, float defaultMm) {
        final SeekBar seekBar = findViewById(seekBarId);
        final TextView label = findViewById(labelId);
        if (seekBar == null || label == null) {
            return;
        }

        float mm = prefs.getFloat(key, defaultMm);
        int progress = Math.round(mm * 100f);
        seekBar.setProgress(progress);
        label.setText(formatMm(progress));

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                label.setText(formatMm(value));
                if (fromUser) {
                    prefs.edit().putFloat(key, value / 100f).apply();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                // no-op
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                // no-op
            }
        });
        prefs.edit().putFloat(key, progress / 100f).apply();
    }

    private String formatMm(int progress) {
        return getString(R.string.unit_mm,
                String.format(java.util.Locale.ROOT, "%.2f", progress / 100f));
    }

    private void updateStatus() {
        TextView status = findViewById(R.id.tv_status);
        if (status == null) {
            return;
        }
        boolean windowBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
        boolean agsl = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU;
        status.setText(getString(R.string.status_template,
                Build.VERSION.SDK_INT,
                getString(windowBlur ? R.string.yes : R.string.no),
                getString(agsl ? R.string.yes : R.string.no)));
    }

    // ======================================================================
    // Root 操作
    // ======================================================================

    private void bindRootActions() {
        Button restart = findViewById(R.id.btn_root_restart);
        if (restart != null) {
            restart.setOnClickListener(v -> restartTargetApp(true));
        }
        Button check = findViewById(R.id.btn_root_check);
        if (check != null) {
            check.setOnClickListener(v -> restartTargetApp(false));
        }
    }

    /**
     * 重启目标应用。
     *
     * @param restart true = 强制停止并重新打开；false = 仅检测 Root 权限
     */
    private void restartTargetApp(final boolean restart) {
        setRootStatus(getString(restart ? R.string.root_restarting : R.string.root_checking));
        setButtonsEnabled(false);

        new Thread(new Runnable() {
            @Override
            public void run() {
                // 1) 申请 root（会触发系统授权弹窗，必须在子线程等待）
                final boolean rooted = RootHelper.isRootAvailable();
                if (!rooted) {
                    postRootStatus(getString(R.string.root_denied));
                    return;
                }
                postRootStatus(getString(R.string.root_available));

                if (!restart) {
                    return;
                }

                // 2) 强制停止目标进程，让 LSPosed 下次启动重新注入
                boolean stopped = RootHelper
                        .forceStopWithFallback(RootHelper.PKG_SMART_CARD)
                        .success;

                try {
                    Thread.sleep(700L);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }

                // 3) 重新拉起（由本应用发起，无需额外权限）
                boolean launched = launchTarget(RootHelper.PKG_SMART_CARD);

                if (launched) {
                    postRootStatus(getString(R.string.root_done));
                } else if (stopped) {
                    postRootStatus(getString(R.string.root_stopped));
                } else {
                    postRootStatus(getString(R.string.root_target_missing));
                }
            }
        }, "lg-root-restart").start();
    }

    private boolean launchTarget(String packageName) {
        try {
            Intent intent = getPackageManager().getLaunchIntentForPackage(packageName);
            if (intent == null) {
                return false;
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(intent);
            return true;
        } catch (Throwable throwable) {
            return false;
        }
    }

    private void postRootStatus(final String text) {
        runOnUiThread(() -> {
            setButtonsEnabled(true);
            setRootStatus(text);
        });
    }

    private void setRootStatus(String text) {
        if (rootStatus != null) {
            rootStatus.setText(text);
        }
    }

    private void setButtonsEnabled(boolean enabled) {
        View restart = findViewById(R.id.btn_root_restart);
        View check = findViewById(R.id.btn_root_check);
        if (restart != null) {
            restart.setEnabled(enabled);
            restart.setAlpha(enabled ? 1f : 0.5f);
        }
        if (check != null) {
            check.setEnabled(enabled);
            check.setAlpha(enabled ? 1f : 0.5f);
        }
    }
}
