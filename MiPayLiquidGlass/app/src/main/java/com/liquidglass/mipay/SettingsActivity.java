package com.liquidglass.mipay;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.liquidglass.mipay.glass.FaceExportDiagnostic;
import com.liquidglass.mipay.glass.IslandBridge;

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
        bindSwitch(R.id.sw_island, Config.KEY_ISLAND_STYLE, true);
        bindSwitch(R.id.sw_island_anim, Config.KEY_ISLAND_ANIMATION, true);
        bindSwitch(R.id.sw_island_overlay, Config.KEY_ISLAND_OVERLAY, true);
        bindSwitch(R.id.sw_hide_system_island, Config.KEY_HIDE_SYSTEM_ISLAND, true);

        bindSeekBar(R.id.seek_blur, R.id.tv_blur_value, Config.KEY_BLUR, 32, true);
        bindSeekBar(R.id.seek_tint, R.id.tv_tint_value, Config.KEY_TINT, 64, false);
        bindSeekBar(R.id.seek_corner, R.id.tv_corner_value, Config.KEY_CORNER, 26, true);
        bindMmSeekBar(R.id.seek_card_radius, R.id.tv_card_radius_value,
                Config.KEY_CARD_RADIUS_MM, 3.18f);

        rootStatus = findViewById(R.id.tv_root_status);
        bindRootActions();

        registerFaceNamesReceiver();
        requestFaceNames();
        showFacePick();

        updateStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 回到设置页时再问一次：用户可能刚打开过小米智能卡，卡名列表就多了
        requestFaceNames();
        showFacePick();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (faceNamesReceiver != null) {
            try {
                unregisterReceiver(faceNamesReceiver);
            } catch (Throwable ignored) {
                // ignore
            }
            faceNamesReceiver = null;
        }
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
        Button sysUi = findViewById(R.id.btn_restart_systemui);
        if (sysUi != null) {
            sysUi.setOnClickListener(v -> restartSystemUi());
        }

        // 刷卡动画预览：两个按钮在 App 内直接播放（一定可见），
        // 用于把「动画本身」与「SystemUI 侧路径」两个变量拆开。
        Button previewOk = findViewById(R.id.btn_preview_island_ok);
        if (previewOk != null) {
            previewOk.setOnClickListener(v -> previewIslandInApp(true));
        }
        Button previewFail = findViewById(R.id.btn_preview_island_fail);
        if (previewFail != null) {
            previewFail.setOnClickListener(v -> previewIslandInApp(false));
        }
        // 第三、四个按钮专门验证「SystemUI 侧」这条路径：分别模拟刷卡成功与失败
        Button previewSystemUi = findViewById(R.id.btn_preview_island_sysui);
        if (previewSystemUi != null) {
            previewSystemUi.setOnClickListener(v -> previewIslandInSystemUi(true));
        }
        Button previewSystemUiFail = findViewById(R.id.btn_preview_island_sysui_fail);
        if (previewSystemUiFail != null) {
            previewSystemUiFail.setOnClickListener(v -> previewIslandInSystemUi(false));
        }
        // 选择卡面：打开列表，看图选卡
        Button pickFace = findViewById(R.id.btn_pick_face);
        if (pickFace != null) {
            pickFace.setOnClickListener(v -> showFacePickList());
        }
    }

    // ======================================================================
    // 卡面选择
    // ======================================================================

    /** 已识别到的卡名（由小米智能卡进程通过广播回传）。 */
    private final java.util.ArrayList<String> faceNames = new java.util.ArrayList<>();
    /** 与卡名一一对应的缩略图（JPEG 字节），可能为空。 */
    private final java.util.ArrayList<byte[]> faceThumbs = new java.util.ArrayList<>();
    private BroadcastReceiver faceNamesReceiver;
    /** 缩略图解码结果缓存，避免每次 inflate 都重新解码。 */
    private final java.util.HashMap<Integer, Bitmap> thumbCache = new java.util.HashMap<>();

    /** 打开卡面列表，让用户直接看图选择。 */
    private void showFacePickList() {
        if (faceNames.isEmpty()) {
            requestFaceNames();
            Toast.makeText(this, R.string.pick_face_querying, Toast.LENGTH_LONG).show();
            return;
        }
        String current = prefs.getString(Config.KEY_FACE_PICK_NAME, "");
        final String[] labels = new String[faceNames.size() + 1];
        labels[0] = getString(R.string.pick_face_auto);
        for (int i = 0; i < faceNames.size(); i++) {
            labels[i + 1] = faceNames.get(i);
        }
        FaceListAdapter adapter = new FaceListAdapter(labels, current);
        new AlertDialog.Builder(this)
                .setTitle(R.string.pick_face_title)
                .setAdapter(adapter, (dialog, which) -> {
                    String choice = which == 0 ? "" : faceNames.get(which - 1);
                    prefs.edit().putString(Config.KEY_FACE_PICK_NAME, choice).apply();
                    // 让小米智能卡立刻读到新选择（它只在进程启动时读一次配置）
                    pokeTargetToReload();
                    showFacePick();
                    dialog.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * 卡面列表适配器：左边缩略图、右边卡名，当前选中项高亮。
     *
     * <p>不用布局文件，行视图在代码里拼 —— 只用到 ImageView + TextView，
     * 省一个 layout 资源，也避免不同主题下的样式差异。</p>
     */
    private final class FaceListAdapter extends BaseAdapter {

        private final String[] labels;
        private final String currentName;

        FaceListAdapter(String[] labels, String currentName) {
            this.labels = labels;
            this.currentName = currentName;
        }

        @Override
        public int getCount() {
            return labels.length;
        }

        @Override
        public Object getItem(int position) {
            return labels[position];
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            RowHolder holder;
            if (convertView == null) {
                LinearLayout row = new LinearLayout(SettingsActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                int padding = dp(12);
                row.setPadding(padding, padding, padding, padding);

                ImageView icon = new ImageView(SettingsActivity.this);
                int iconWidth = dp(72);
                int iconHeight = dp(45);
                LinearLayout.LayoutParams iconParams =
                        new LinearLayout.LayoutParams(iconWidth, iconHeight);
                iconParams.setMarginEnd(dp(12));
                icon.setLayoutParams(iconParams);
                icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
                row.addView(icon);

                TextView label = new TextView(SettingsActivity.this);
                label.setTextSize(15f);
                label.setTextColor(getResources().getColor(R.color.text_primary));
                label.setLayoutParams(new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                row.addView(label);

                holder = new RowHolder(icon, label);
                row.setTag(holder);
                convertView = row;
            } else {
                holder = (RowHolder) convertView.getTag();
            }

            holder.label.setText(labels[position]);
            boolean selected = position == 0
                    ? (currentName == null || currentName.isEmpty())
                    : labels[position].equals(currentName);
            holder.label.setAlpha(selected ? 1f : 0.86f);
            holder.label.setText(selected
                    ? "✓ " + labels[position] : labels[position]);
            holder.icon.setImageBitmap(thumbAt(position));
            return convertView;
        }

        private Bitmap thumbAt(int position) {
            if (position == 0) {
                return null; // 「自动」没有缩略图
            }
            int index = position - 1;
            if (index >= faceThumbs.size()) {
                return null;
            }
            Bitmap cached = thumbCache.get(index);
            if (cached != null) {
                return cached;
            }
            byte[] data = faceThumbs.get(index);
            if (data == null || data.length == 0) {
                return null;
            }
            try {
                Bitmap decoded = BitmapFactory.decodeByteArray(data, 0, data.length);
                if (decoded != null) {
                    thumbCache.put(index, decoded);
                }
                return decoded;
            } catch (Throwable ignored) {
                return null;
            }
        }
    }

    private static final class RowHolder {
        final ImageView icon;
        final TextView label;

        RowHolder(ImageView icon, TextView label) {
            this.icon = icon;
            this.label = label;
        }
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }

    /**
     * 通知小米智能卡进程刷新配置。
     *
     * <p>被 Hook 的进程只在启动时读一次模块配置，所以切换卡面后如果不重启它，
     * 新选择不会生效 —— 这正是「切换了却拿不到卡面」的原因之一。</p>
     */
    private void pokeTargetToReload() {
        try {
            Intent poke = new Intent(FaceExportDiagnostic.ACTION_RELOAD_CONFIG);
            poke.setPackage("com.miui.tsmclient");
            sendBroadcast(poke);
        } catch (Throwable ignored) {
            // ignore
        }
    }

    /** 把当前选择显示到按钮与说明上。 */
    private void showFacePick() {
        String choice = prefs.getString(Config.KEY_FACE_PICK_NAME, "");
        Button button = findViewById(R.id.btn_pick_face);
        if (button != null) {
            button.setText(getString(R.string.pick_face_button,
                    choice == null || choice.isEmpty() ? getString(R.string.pick_face_auto) : choice));
        }
        TextView detail = findViewById(R.id.tv_pick_face);
        if (detail != null) {
            if (faceNames.isEmpty()) {
                detail.setText(R.string.opt_pick_face_desc);
            } else {
                detail.setText(getString(R.string.pick_face_summary, faceNames.size()));
            }
        }
    }

    /** 向小米智能卡进程查询已识别到的卡名。 */
    private void requestFaceNames() {
        try {
            Intent query = new Intent(FaceExportDiagnostic.ACTION_QUERY_NAMES);
            query.setPackage("com.miui.tsmclient");
            sendBroadcast(query);
        } catch (Throwable ignored) {
            // ignore
        }
    }

    /** 注册接收卡名列表的接收器。 */
    private void registerFaceNamesReceiver() {
        try {
            faceNamesReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    try {
                        java.util.ArrayList<String> names = intent == null ? null
                                : intent.getStringArrayListExtra(FaceExportDiagnostic.EXTRA_NAMES);
                        if (names == null || names.isEmpty()) {
                            // 辅进程（:daemon）没有卡面缓存，只会回空列表。
                            // 直接忽略，避免把主进程刚送来的好数据清掉。
                            return;
                        }
                        faceNames.clear();
                        faceNames.addAll(names);
                        thumbCache.clear();
                        faceThumbs.clear();
                        try {
                            Object raw = intent.getSerializableExtra(FaceExportDiagnostic.EXTRA_THUMBS);
                            if (raw instanceof java.util.ArrayList) {
                                for (Object item : (java.util.ArrayList<?>) raw) {
                                    faceThumbs.add(item instanceof byte[] ? (byte[]) item : null);
                                }
                            }
                        } catch (Throwable ignored) {
                            // 缩略图缺失不影响按名字选择
                        }
                        showFacePick();
                        Toast.makeText(SettingsActivity.this,
                                getString(R.string.pick_face_found, faceNames.size()),
                                Toast.LENGTH_SHORT).show();
                    } catch (Throwable ignored) {
                        // ignore
                    }
                }
            };
            IntentFilter filter = new IntentFilter(FaceExportDiagnostic.ACTION_NAMES_RESULT);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(faceNamesReceiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                registerReceiver(faceNamesReceiver, filter);
            }
        } catch (Throwable ignored) {
            // ignore
        }
    }

    /** 在模块自己的进程里播放动画：不依赖 SystemUI，一定能看到。 */
    private void previewIslandInApp(boolean success) {
        Intent intent = new Intent(this, PreviewActivity.class);
        intent.putExtra(PreviewActivity.EXTRA_SUCCESS, success);
        intent.putExtra(PreviewActivity.EXTRA_AMOUNT, "¥2.00");
        intent.putExtra(PreviewActivity.EXTRA_BALANCE, "余额 ¥128.50");
        startActivity(intent);
    }

    /**
     * 发送给 SystemUI 播放（方案 B）。
     *
     * <p>模拟一次刷卡：成功时金额 ¥2.00 / 余额 ¥128.50，失败时只带状态文案。
     * 若顶部没有出现动画，说明 SystemUI 侧路径有问题 ——
     * 常见原因是 LSPosed 作用域未勾选「系统界面」，或屏幕未点亮。</p>
     */
    private void previewIslandInSystemUi(boolean success) {
        String pick = prefs.getString(Config.KEY_FACE_PICK_NAME, "");
        if (success) {
            IslandBridge.send(this, "¥2.00", "余额 ¥128.50", true, pick);
            Toast.makeText(this, R.string.preview_island_sent, Toast.LENGTH_LONG).show();
        } else {
            IslandBridge.send(this, "刷卡失败", "余额 ¥128.50", false, pick);
            Toast.makeText(this, R.string.preview_island_sent_fail, Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 重启 SystemUI。
     *
     * <p>超级岛 / 状态栏相关内容由 SystemUI 承载，改完配置需要它重新加载。
     * SystemUI 被终止后系统会自动拉起，因此「杀掉即重启」；
     * 期间状态栏会短暂消失几秒。</p>
     */
    private void restartSystemUi() {
        setRootStatus(getString(R.string.root_systemui_restarting));
        setButtonsEnabled(false);

        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean rooted = RootHelper.isRootAvailable();
                if (!rooted) {
                    postRootStatus(getString(R.string.root_denied));
                    return;
                }
                RootHelper.Result result = RootHelper.restartSystemUi();
                postRootStatus(getString(
                        result.success ? R.string.root_systemui_done : R.string.root_systemui_failed));
            }
        }, "lg-restart-systemui").start();
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
        View sysUi = findViewById(R.id.btn_restart_systemui);
        if (restart != null) {
            restart.setEnabled(enabled);
            restart.setAlpha(enabled ? 1f : 0.5f);
        }
        if (check != null) {
            check.setEnabled(enabled);
            check.setAlpha(enabled ? 1f : 0.5f);
        }
        if (sysUi != null) {
            sysUi.setEnabled(enabled);
            sysUi.setAlpha(enabled ? 1f : 0.5f);
        }
    }
}
