package com.novelcraft.writer;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class SettingsActivity extends android.app.Activity {

    private static final String[] PROVIDERS = {
            "DeepSeek（深度求索）",
            "英伟达 NVIDIA",
            "OpenAI",
            "月之暗面 Kimi",
            "通义千问 Qwen",
            "智谱 GLM",
            "硅基流动 SiliconFlow",
            "零一万物 Yi",
            "自定义"
    };
    private static final String[] BASES = {
            "https://api.deepseek.com",
            "https://integrate.api.nvidia.com",
            "https://api.openai.com",
            "https://api.moonshot.cn",
            "https://dashscope.aliyuncs.com/compatible-mode/v1",
            "https://open.bigmodel.cn/api/paas/v4",
            "https://api.siliconflow.cn",
            "https://api.lingyiwanwu.com",
            ""
    };
    private static final String[] MODELS = {
            "deepseek-chat|deepseek-reasoner|deepseek-coder",
            "deepseek-ai/deepseek-v4-pro-0813|deepseek-ai/deepseek-v4-flash-0731|nvidia/nemotron-3-super-120b-a12b|nvidia/llama-3.1-nemotron-70b-instruct|moonshotai/kimi-k3|writer/palmyra-creative-122b|meta/muse-glimmer-30b",
            "gpt-4o-mini|gpt-4o|gpt-4.1-mini|gpt-4.1",
            "moonshot-v1-8k|moonshot-v1-32k|moonshot-v1-128k",
            "qwen-plus|qwen-max|qwen-turbo|qwen2.5-72b-instruct",
            "glm-4-plus|glm-4-air|glm-4-flash",
            "deepseek-ai/DeepSeek-V3|Qwen/Qwen2.5-72B-Instruct|meta-llama/Llama-3.3-70B-Instruct",
            "yi-lightning|yi-large",
            ""
    };

    private EditText etBaseUrl, etApiKey, etModel;
    private Spinner spinnerProvider, spinnerModel;
    private Button btnToggleKey, btnTest, btnSave;
    private Toast toast;

    private NovelService service;
    private boolean keyVisible = false;
    private String CUSTOM = "";
    private final List<String> modelOptions = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        CUSTOM = getString(R.string.model_custom);
        service = new NovelService(this);
        etBaseUrl = findViewById(R.id.etBaseUrl);
        etApiKey = findViewById(R.id.etApiKey);
        etModel = findViewById(R.id.etModel);
        spinnerProvider = findViewById(R.id.spinnerProvider);
        spinnerModel = findViewById(R.id.spinnerModel);
        btnToggleKey = findViewById(R.id.btnToggleKey);
        btnTest = findViewById(R.id.btnTest);
        btnSave = findViewById(R.id.btnSave);

        setupBottomNav();
        setupExtraSettings();

        ArrayAdapter<String> pa = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, PROVIDERS);
        pa.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerProvider.setAdapter(pa);

        spinnerProvider.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!BASES[position].isEmpty()) {
                    etBaseUrl.setText(BASES[position]);
                }
                buildModelSpinner(position);
                etApiKey.setText(service.getProviderKey(PROVIDERS[position]));
                service.saveProvider(PROVIDERS[position]);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        Button btnPaste = findViewById(R.id.btnPaste);
        btnPaste.setOnClickListener(v -> pasteKey());
        btnToggleKey.setOnClickListener(v -> toggleKeyVisible());
        btnTest.setOnClickListener(v -> testConnection());
        btnSave.setOnClickListener(v -> saveSettings());

        loadSettings();
    }

    private void buildModelSpinner(int providerIndex) {
        modelOptions.clear();
        String all = MODELS[providerIndex];
        for (String s : all.split("\\|")) {
            if (!s.isEmpty()) modelOptions.add(s.trim());
        }
        modelOptions.add(CUSTOM);
        ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, modelOptions);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerModel.setAdapter(a);
        spinnerModel.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                boolean custom = modelOptions.get(position).equals(CUSTOM);
                etModel.setVisibility(custom ? View.VISIBLE : View.GONE);
                if (custom) {
                    etModel.requestFocus();
                } else {
                    etModel.setText(modelOptions.get(position));
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        etModel.setVisibility(View.GONE);
        if (!modelOptions.isEmpty()) etModel.setText(modelOptions.get(0));
    }

    private void loadSettings() {
        etBaseUrl.setText(service.getApiBaseUrl());
        etApiKey.setText(service.getApiKey());
        String savedModel = service.getModel();

        int idx = 0;
        String savedProvider = service.getProvider();
        for (int i = 0; i < PROVIDERS.length; i++) {
            if (PROVIDERS[i].equals(savedProvider)) {
                idx = i;
                break;
            }
            if (!BASES[i].isEmpty() && BASES[i].equalsIgnoreCase(service.getApiBaseUrl())) {
                idx = i;
            }
        }
        spinnerProvider.setSelection(idx);
        buildModelSpinner(idx);
        int m = modelOptions.indexOf(savedModel);
        if (m >= 0) {
            spinnerModel.setSelection(m);
            etModel.setText(savedModel);
        } else if (!savedModel.isEmpty()) {
            int custom = modelOptions.indexOf(CUSTOM);
            spinnerModel.setSelection(custom);
            etModel.setText(savedModel);
        }
    }

    private void pasteKey() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null && cm.hasPrimaryClip()) {
            ClipData clip = cm.getPrimaryClip();
            if (clip != null && clip.getItemCount() > 0) {
                CharSequence text = clip.getItemAt(0).getText();
                if (text != null && text.length() > 0) {
                    etApiKey.setText(text.toString().trim());
                    etApiKey.setSelection(etApiKey.getText().length());
                    showToast(getString(R.string.toast_pasted));
                    return;
                }
            }
        }
        showToast(getString(R.string.toast_no_clipboard));
    }

    private void toggleKeyVisible() {
        keyVisible = !keyVisible;
        if (keyVisible) {
            etApiKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
            btnToggleKey.setText(R.string.btn_hide);
        } else {
            etApiKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            btnToggleKey.setText(R.string.btn_show);
        }
        etApiKey.setSelection(etApiKey.getText().length());
    }

    private String baseUrl() {
        return etBaseUrl.getText().toString().trim();
    }

    private String apiKey() {
        return etApiKey.getText().toString().trim();
    }

    private String model() {
        int pos = spinnerModel.getSelectedItemPosition();
        if (pos < 0 || pos >= modelOptions.size()) return etModel.getText().toString().trim();
        String sel = modelOptions.get(pos);
        if (sel.equals(CUSTOM)) {
            return etModel.getText().toString().trim();
        }
        return sel;
    }

    private String provider() {
        int pos = spinnerProvider.getSelectedItemPosition();
        return (pos < 0) ? PROVIDERS[0] : PROVIDERS[pos];
    }

    private void testConnection() {
        if (!validateFields()) return;
        btnTest.setEnabled(false);
        showToast(getString(R.string.toast_testing));
        service.testConnection(new NovelService.TestCallback() {
            @Override
            public void onSuccess() {
                btnTest.setEnabled(true);
                showToast(getString(R.string.toast_success));
            }

            @Override
            public void onError(String error) {
                btnTest.setEnabled(true);
                showToast(getString(R.string.toast_failed, error));
            }
        });
    }

    private void saveSettings() {
        if (!validateFields()) return;
        service.saveSettings(baseUrl(), apiKey(), model());
        service.saveProviderKey(provider(), apiKey());
        service.saveProvider(provider());
        showToast(getString(R.string.settings_saved));
        finish();
    }

    private boolean validateFields() {
        if (baseUrl().isEmpty()) {
            showToast(getString(R.string.error_base_url_empty));
            return false;
        }
        if (apiKey().isEmpty()) {
            showToast(getString(R.string.error_api_key_empty));
            return false;
        }
        if (model().isEmpty()) {
            showToast(getString(R.string.error_model_empty));
            return false;
        }
        return true;
    }

    private void setupExtraSettings() {
        // 创作偏好
        android.content.SharedPreferences prefs = getSharedPreferences("editor_prefs", MODE_PRIVATE);
        CheckBox cb = findViewById(R.id.cbDefaultAutoRevise);
        cb.setChecked(prefs.getBoolean("default_auto_revise", false));
        cb.setOnCheckedChangeListener((buttonView, isChecked) ->
                prefs.edit().putBoolean("default_auto_revise", isChecked).apply());

        // 关于版本
        TextView tvVer = findViewById(R.id.tvAboutVersion);
        tvVer.setText(getString(R.string.about_version, appVersionName()));

        // 数据管理
        findViewById(R.id.btnClearCache).setOnClickListener(v -> clearCache());
        findViewById(R.id.btnResetPrefs).setOnClickListener(v -> confirmResetPrefs());

        // 支持
        findViewById(R.id.btnSupport).setOnClickListener(v -> showSupport());
    }

    private String appVersionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "2.8.0";
        }
    }

    private void clearCache() {
        try {
            java.io.File cache = getCacheDir();
            deleteRecursive(cache);
            showToast(getString(R.string.cache_cleared));
        } catch (Exception e) {
            showToast(getString(R.string.cache_cleared));
        }
    }

    private void deleteRecursive(java.io.File f) {
        if (f == null || !f.exists()) return;
        java.io.File[] fs = f.listFiles();
        if (fs != null) for (java.io.File c : fs) deleteRecursive(c);
        f.delete();
    }

    private void confirmResetPrefs() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.btn_reset_prefs)
                .setMessage(R.string.reset_prefs_confirm)
                .setPositiveButton(R.string.btn_ok, (d, w) -> {
                    getSharedPreferences("editor_prefs", MODE_PRIVATE).edit().clear().apply();
                    showToast(getString(R.string.prefs_reset));
                })
                .setNegativeButton(R.string.btn_cancel, null)
                .show();
    }

    private void showSupport() {
        View content = getLayoutInflater().inflate(R.layout.dialog_support, null);
        new AlertDialog.Builder(this)
                .setTitle(R.string.support_title)
                .setView(content)
                .setPositiveButton(R.string.btn_close, null)
                .show();
    }

    private void setupBottomNav() {
        setTabActive(R.id.navSettings);
        findViewById(R.id.navHome).setOnClickListener(v -> finish());
        findViewById(R.id.navLibrary).setOnClickListener(v ->
                startActivity(new Intent(this, LibraryActivity.class)));
        findViewById(R.id.navFactory).setOnClickListener(v -> goMain("factory"));
        findViewById(R.id.navQuality).setOnClickListener(v -> goMain("quality"));
    }

    private void goMain(String action) {
        Intent it = new Intent(this, MainActivity.class);
        it.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (action != null) it.putExtra("action", action);
        startActivity(it);
    }

    private void setTabActive(int id) {
        int[] tabs = {R.id.navHome, R.id.navLibrary, R.id.navFactory, R.id.navQuality, R.id.navSettings};
        for (int t : tabs) {
            Button b = findViewById(t);
            if (b == null) continue;
            boolean active = t == id;
            b.setTextColor(active ? getColor(R.color.primary_color) : getColor(R.color.secondary_text));
            b.setTypeface(null, active ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        }
    }

    private void showToast(String msg) {
        if (toast != null) toast.cancel();
        toast = Toast.makeText(this, msg, Toast.LENGTH_LONG);
        toast.show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        service.shutdown();
    }
}
