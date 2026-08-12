package pt.dashboardauto;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.widget.Toast;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.CheckBox;
import android.widget.ScrollView;
import android.graphics.drawable.GradientDrawable;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.pm.ResolveInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;

public class MainActivity extends android.app.Activity {
    private static final int BACKGROUND = Color.rgb(10, 10, 14);
    private static final int SURFACE = Color.rgb(27, 27, 34);
    private static final int ACCENT = Color.rgb(255, 55, 95);
    private static final int TEXT_MUTED = Color.rgb(170, 170, 180);
    private final ArrayList<AppChoice> navigationApps = new ArrayList<>();
    private final ArrayList<AppChoice> musicApps = new ArrayList<>();
    private final ArrayList<AppChoice> allApps = new ArrayList<>();
    private final ArrayList<BluetoothChoice> bluetoothDevices = new ArrayList<>();
    private Spinner navigationSpinner;
    private Spinner musicSpinner;
    private android.content.SharedPreferences preferences;

    private static class BluetoothChoice {
        final String label;
        final String address;
        BluetoothChoice(String label, String address) { this.label = label; this.address = address; }
        @Override public String toString() { return label; }
    }

    private static class AppChoice {
        final String label;
        final String packageName;
        AppChoice(String label, String packageName) { this.label = label; this.packageName = packageName; }
        @Override public String toString() { return label; }
    }

    private int dp(float value) { return (int) (value * getResources().getDisplayMetrics().density + .5f); }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = getSharedPreferences("dashboard_auto", MODE_PRIVATE);
        loadApps();
        loadBluetoothDevices();
        showAnimatedContent(preferences.getBoolean("onboarding_complete", false) ? buildScreen() : buildOnboarding());
    }

    @Override protected void onResume() {
        super.onResume();
        if (preferences != null) {
            loadBluetoothDevices();
            showAnimatedContent(preferences.getBoolean("onboarding_complete", false) ? buildScreen() : buildOnboarding());
        }
    }

    private void loadApps() {
        navigationApps.clear();
        musicApps.clear();
        allApps.clear();
        Intent launcher = new Intent(Intent.ACTION_MAIN);
        launcher.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> installed = getPackageManager().queryIntentActivities(launcher, 0);
        LinkedHashMap<String, AppChoice> unique = new LinkedHashMap<>();
        for (ResolveInfo info : installed) {
            String packageName = info.activityInfo.packageName;
            String label = info.loadLabel(getPackageManager()).toString();
            if (packageName.equals(getPackageName())) continue;
            unique.put(packageName, new AppChoice(label, packageName));
        }
        allApps.add(new AppChoice("Escolher mais tarde", ""));
        navigationApps.add(new AppChoice("Escolher mais tarde", ""));
        musicApps.add(new AppChoice("Escolher mais tarde", ""));
        for (AppChoice app : unique.values()) {
            allApps.add(app);
            String value = (app.label + " " + app.packageName).toLowerCase(Locale.ROOT);
            if (isNavigationApp(value)) navigationApps.add(app);
            if (isMusicApp(value)) musicApps.add(app);
        }
        if (navigationApps.size() == 1) navigationApps.addAll(allApps.subList(1, allApps.size()));
        if (musicApps.size() == 1) musicApps.addAll(allApps.subList(1, allApps.size()));
    }

    private boolean isNavigationApp(String value) {
        return value.contains("waze") || value.contains("google maps") || value.contains("maps") || value.contains("here wego") || value.contains("tomtom") || value.contains("sygic") || value.contains("navigation") || value.contains("navega");
    }

    private boolean isMusicApp(String value) {
        return value.contains("spotify") || value.contains("youtube music") || value.contains("apple music") || value.contains("poweramp") || value.contains("tidal") || value.contains("deezer") || value.contains("music") || value.contains("música") || value.contains("radio") || value.contains("podcast") || value.contains("vlc");
    }

    private void loadBluetoothDevices() {
        bluetoothDevices.clear();
        bluetoothDevices.add(new BluetoothChoice("Não ativar automaticamente", ""));
        if (!PermissionManager.canConnectBluetooth(this)) {
            bluetoothDevices.add(new BluetoothChoice("Autoriza Bluetooth para escolher um dispositivo", ""));
            return;
        }
        BluetoothManager bluetoothManager = getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = bluetoothManager == null ? null : bluetoothManager.getAdapter();
        if (adapter == null) {
            bluetoothDevices.add(new BluetoothChoice("Bluetooth indisponível neste dispositivo", ""));
            return;
        }
        try {
            for (BluetoothDevice device : adapter.getBondedDevices()) {
                String name = device.getName();
                String address = device.getAddress();
                String suffix = address == null || address.length() < 5 ? "" : " • " + address.substring(address.length() - 5);
                bluetoothDevices.add(new BluetoothChoice((name == null ? "Dispositivo Bluetooth" : name) + suffix, address));
            }
        } catch (SecurityException ignored) {
            bluetoothDevices.add(new BluetoothChoice("Autoriza Bluetooth para escolher um dispositivo", ""));
        }
    }

    private View buildScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(28), dp(24), dp(24));
        root.setBackgroundColor(BACKGROUND);

        TextView eyebrow = text("CAR MODE  •  APPLE-INSPIRED", 11, ACCENT);
        root.addView(eyebrow);
        TextView title = text("DriveDeck", 30, Color.WHITE);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title, margin(0, 4, 0, 0));
        root.addView(text("Tudo o que precisas, sem tirar os olhos da estrada.", 16, TEXT_MUTED), margin(0, 8, 0, 24));

        Button navigation = primaryAction("🗺  " + selectedLabel(navigationApps, "navigation_app", "Abrir navegação"));
        navigation.setOnClickListener(v -> openPackage(selectedPackage(navigationSpinner)));
        root.addView(navigation, margin(0, 0, 0, 12));

        Button music = primaryAction("♫  " + selectedLabel(musicApps, "music_app", "Abrir música"));
        music.setOnClickListener(v -> openPackage(selectedPackage(musicSpinner)));
        root.addView(music, margin(0, 0, 0, 12));

        Button start = button(PermissionManager.canDrawOverlay(this) ? "Iniciar Car Mode" : "Autorizar Car Mode");
        start.setOnClickListener(v -> startOverlay());
        root.addView(start, margin(0, 0, 0, 20));

        Button settings = secondaryAction("⚙  Definições");
        LinearLayout settingsPanel = new LinearLayout(this);
        settingsPanel.setOrientation(LinearLayout.VERTICAL);
        settingsPanel.setPadding(dp(18), dp(18), dp(18), dp(18));
        settingsPanel.setBackground(surfaceBackground());
        settingsPanel.setVisibility(View.GONE);
        settingsPanel.addView(text("As tuas apps", 19, Color.WHITE));
        settingsPanel.addView(text("Escolhe as apps e permissões do modo condução.", 13, TEXT_MUTED), margin(0, 6, 0, 16));
        settingsPanel.addView(text("NAVEGAÇÃO", 11, ACCENT), margin(0, 0, 0, 4));
        settingsPanel.addView(appSelector(true), margin(0, 0, 0, 12));
        settingsPanel.addView(text("MÚSICA", 11, ACCENT), margin(0, 0, 0, 4));
        settingsPanel.addView(appSelector(false), margin(0, 0, 0, 12));
        Button mediaAccess = secondaryAction(PermissionManager.isMediaAccessEnabled(this) ? "✓  Controlos de música autorizados" : "Permitir controlos de música");
        mediaAccess.setOnClickListener(v -> PermissionManager.openMediaSettings(this));
        settingsPanel.addView(mediaAccess, margin(0, 0, 0, 10));
        settingsPanel.addView(autoBluetoothOption(), margin(0, 0, 0, 4));
        settingsPanel.addView(bluetoothSpinner(), margin(0, 0, 0, 4));
        settingsPanel.addView(closeOnDisconnectOption());
        settingsPanel.addView(text("AO LIGAR BLUETOOTH", 11, ACCENT), margin(0, 12, 0, 4));
        settingsPanel.addView(bluetoothLaunchModeSpinner());
        settingsPanel.addView(autoPlayMusicOption(), margin(0, 12, 0, 0));
        settings.setOnClickListener(v -> {
            boolean show = settingsPanel.getVisibility() != View.VISIBLE;
            settingsPanel.setVisibility(View.VISIBLE);
            settingsPanel.setAlpha(show ? 0f : 1f);
            settingsPanel.setTranslationY(show ? dp(-8) : 0);
            if (show) settingsPanel.animate().alpha(1f).translationY(0).setDuration(240).start();
            else settingsPanel.setVisibility(View.GONE);
        });
        root.addView(settings, margin(0, 0, 0, 10));
        root.addView(settingsPanel);
        root.addView(text("Definições ficam guardadas automaticamente.", 12, Color.GRAY), margin(0, 14, 0, 0));
        return scroll(root);
    }

    private View buildOnboarding() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(34), dp(24), dp(24));
        root.setBackgroundColor(BACKGROUND);
        root.addView(text("BEM-VINDO AO CAR MODE", 11, ACCENT));
        TextView title = text("Vamos preparar a tua experiência de condução.", 28, Color.WHITE);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title, margin(0, 8, 0, 12));
        root.addView(text("Uma configuração simples. Uma experiência muito mais calma.", 16, TEXT_MUTED), margin(0, 0, 0, 22));

        root.addView(step("1", "Escolhe as tuas aplicações", "Define a app de navegação e a app de música que queres usar."), margin(0, 0, 0, 14));
        root.addView(text("NAVEGAÇÃO", 11, ACCENT), margin(0, 0, 0, 4));
        root.addView(appSelector(true), margin(0, 0, 0, 12));
        root.addView(text("MÚSICA", 12, Color.rgb(140, 230, 192)), margin(0, 0, 0, 4));
        root.addView(appSelector(false), margin(0, 0, 0, 16));
        root.addView(step("2", "Ativa o modo condução", "O overlay fica disponível por cima do Waze, Maps e outras apps."), margin(0, 0, 0, 8));
        Button overlayPermission = button("Dar permissão ao overlay");
        overlayPermission.setOnClickListener(v -> PermissionManager.openOverlaySettings(this));
        root.addView(overlayPermission, margin(0, 0, 0, 10));
        Button notificationsPermission = button(PermissionManager.canPostNotifications(this) ? "Notificações autorizadas" : "Permitir notificações do Car Mode");
        notificationsPermission.setOnClickListener(v -> PermissionManager.requestNotifications(this));
        root.addView(notificationsPermission, margin(0, 0, 0, 10));
        root.addView(autoBluetoothOption(), margin(0, 0, 0, 10));
        root.addView(bluetoothSpinner(), margin(0, 0, 0, 8));
        root.addView(closeOnDisconnectOption(), margin(0, 0, 0, 16));
        root.addView(step("3", "Concluir", "Quando o Bluetooth do carro ligar, o overlay pode iniciar automaticamente."), margin(0, 0, 0, 8));
        Button finish = button("Começar a usar");
        finish.setOnClickListener(v -> {
            preferences.edit().putBoolean("onboarding_complete", true).apply();
            if (PermissionManager.canDrawOverlay(this)) startOverlay();
            showAnimatedContent(buildScreen());
        });
        root.addView(finish, margin(0, 0, 0, 10));
        root.addView(text("Podes saltar o overlay e ativá-lo mais tarde. A app nunca abre outras apps sem a tua configuração.", 12, Color.GRAY));
        return scroll(root);
    }

    private TextView step(String number, String title, String description) {
        TextView view = text(number + "  " + title + "\n" + description, 14, Color.WHITE);
        view.setPadding(dp(12), dp(10), dp(12), dp(10));
        return view;
    }

    private ScrollView scroll(View content) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BACKGROUND);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -1));
        return scroll;
    }

    private CheckBox autoBluetoothOption() {
        CheckBox option = new CheckBox(this);
        option.setText("Iniciar automaticamente ao ligar ao dispositivo escolhido");
        option.setTextColor(Color.WHITE);
        option.setTextSize(14);
        option.setChecked(preferences.getBoolean("auto_bluetooth", false));
        option.setOnCheckedChangeListener((button, checked) -> {
            preferences.edit().putBoolean("auto_bluetooth", checked).apply();
            if (checked) PermissionManager.requestBluetooth(this);
        });
        return option;
    }

    private Spinner bluetoothSpinner() {
        Spinner spinner = new Spinner(this);
        ArrayAdapter<BluetoothChoice> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, bluetoothDevices);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        String saved = preferences.getString("bluetooth_device_address", "");
        for (int i = 0; i < bluetoothDevices.size(); i++) if (bluetoothDevices.get(i).address.equals(saved)) { spinner.setSelection(i); break; }
        spinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                preferences.edit().putString("bluetooth_device_address", bluetoothDevices.get(position).address).apply();
            }
            public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        return spinner;
    }

    private CheckBox closeOnDisconnectOption() {
        CheckBox option = new CheckBox(this);
        option.setText("Fechar o overlay quando este dispositivo desligar");
        option.setTextColor(Color.WHITE);
        option.setTextSize(14);
        option.setChecked(preferences.getBoolean("close_on_bluetooth_disconnect", true));
        option.setOnCheckedChangeListener((button, checked) -> preferences.edit().putBoolean("close_on_bluetooth_disconnect", checked).apply());
        return option;
    }

    private Spinner bluetoothLaunchModeSpinner() {
        Spinner spinner = new Spinner(this);
        String[] modes = {"Abrir Car Mode completo", "Iniciar apenas a música"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, modes);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        if ("music_only".equals(preferences.getString("bluetooth_launch_mode", "car_mode"))) spinner.setSelection(1);
        spinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                preferences.edit().putString("bluetooth_launch_mode", position == 1 ? "music_only" : "car_mode").apply();
            }
            public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        return spinner;
    }

    private CheckBox autoPlayMusicOption() {
        CheckBox option = new CheckBox(this);
        option.setText("Reproduzir música automaticamente ao abrir o Car Mode");
        option.setTextColor(Color.WHITE);
        option.setTextSize(14);
        option.setChecked(preferences.getBoolean("auto_play_music_on_car_mode", true));
        option.setOnCheckedChangeListener((button, checked) -> preferences.edit().putBoolean("auto_play_music_on_car_mode", checked).apply());
        return option;
    }

    private void startOverlay() {
        Intent service = new Intent(this, OverlayService.class);
        service.putExtra("launch_apps", true);
        service.putExtra("launch_mode", "car_mode");
        if (!PermissionManager.canDrawOverlay(this)) {
            Toast.makeText(this, "Autoriza primeiro o overlay.", Toast.LENGTH_SHORT).show();
            PermissionManager.openOverlaySettings(this);
            return;
        }
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(service); else startService(service);
        } catch (RuntimeException error) {
            Toast.makeText(this, "Não foi possível iniciar o Car Mode. Verifica as permissões.", Toast.LENGTH_LONG).show();
        }
    }

    private void openPackage(String packageName) {
        if (packageName == null || packageName.isEmpty()) return;
        Intent launch = getPackageManager().getLaunchIntentForPackage(packageName);
        if (launch != null) startActivity(launch);
    }

    private Spinner spinner(ArrayList<AppChoice> choices, String preferenceKey) {
        Spinner spinner = new Spinner(this);
        ArrayAdapter<AppChoice> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, choices);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        String saved = preferences.getString(preferenceKey, "");
        for (int i = 0; i < choices.size(); i++) if (choices.get(i).packageName.equals(saved)) { spinner.setSelection(i); break; }
        spinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) { preferences.edit().putString(preferenceKey, choices.get(position).packageName).apply(); }
            public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        return spinner;
    }

    private LinearLayout appSelector(boolean navigation) {
        ArrayList<AppChoice> suggestions = navigation ? navigationApps : musicApps;
        String key = navigation ? "navigation_app" : "music_app";
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        Spinner selector = new Spinner(this);
        ArrayAdapter<AppChoice> initialAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, suggestions);
        initialAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        selector.setAdapter(initialAdapter);
        selector.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                AppChoice choice = (AppChoice) parent.getItemAtPosition(position);
                preferences.edit().putString(key, choice.packageName).apply();
            }
            public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        String saved = preferences.getString(key, "");
        for (int i = 0; i < suggestions.size(); i++) if (suggestions.get(i).packageName.equals(saved)) { selector.setSelection(i); break; }
        if (navigation) navigationSpinner = selector; else musicSpinner = selector;
        group.addView(selector);
        CheckBox all = new CheckBox(this);
        all.setText("Mostrar todas as apps instaladas");
        all.setTextColor(TEXT_MUTED);
        all.setTextSize(12);
        all.setPadding(0, 0, 0, 0);
        all.setOnCheckedChangeListener((button, checked) -> {
            ArrayList<AppChoice> values = checked ? allApps : suggestions;
            ArrayAdapter<AppChoice> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, values);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            selector.setAdapter(adapter);
            String savedChoice = preferences.getString(key, "");
            for (int i = 0; i < values.size(); i++) if (values.get(i).packageName.equals(savedChoice)) { selector.setSelection(i); break; }
        });
        group.addView(all);
        return group;
    }

    private String selectedPackage(Spinner spinner) { return ((AppChoice) spinner.getSelectedItem()).packageName; }

    private TextView text(String value, float size, int color) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color); return view;
    }
    private Button button(String value) {
        Button b = new Button(this);
        b.setText(value); b.setAllCaps(false); b.setTextColor(Color.WHITE);
        GradientDrawable background = new GradientDrawable();
        background.setColor(ACCENT); background.setCornerRadius(dp(14));
        b.setBackground(background);
        b.setOnTouchListener((view, event) -> {
            if (event.getAction() == android.view.MotionEvent.ACTION_DOWN) view.animate().scaleX(.97f).scaleY(.97f).setDuration(90).start();
            if (event.getAction() == android.view.MotionEvent.ACTION_UP || event.getAction() == android.view.MotionEvent.ACTION_CANCEL) view.animate().scaleX(1f).scaleY(1f).setDuration(140).start();
            return false;
        });
        return b;
    }
    private Button primaryAction(String value) {
        Button b = button(value);
        b.setTextSize(17);
        b.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
        b.setPadding(dp(20), 0, dp(20), 0);
        b.setMinHeight(dp(64));
        return b;
    }
    private Button secondaryAction(String value) {
        Button b = new Button(this);
        b.setText(value); b.setAllCaps(false); b.setTextColor(Color.WHITE); b.setTextSize(14);
        b.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
        b.setPadding(dp(16), 0, dp(16), 0); b.setMinHeight(dp(52));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(42, 42, 52)); background.setCornerRadius(dp(14));
        b.setBackground(background);
        b.setOnTouchListener((view, event) -> {
            if (event.getAction() == android.view.MotionEvent.ACTION_DOWN) view.animate().scaleX(.98f).scaleY(.98f).setDuration(90).start();
            if (event.getAction() == android.view.MotionEvent.ACTION_UP || event.getAction() == android.view.MotionEvent.ACTION_CANCEL) view.animate().scaleX(1f).scaleY(1f).setDuration(140).start();
            return false;
        });
        return b;
    }
    private String selectedLabel(ArrayList<AppChoice> choices, String key, String fallback) {
        String saved = preferences.getString(key, "");
        for (AppChoice choice : choices) if (choice.packageName.equals(saved) && !choice.packageName.isEmpty()) return choice.label;
        return fallback;
    }
    private GradientDrawable surfaceBackground() {
        GradientDrawable background = new GradientDrawable();
        background.setColor(SURFACE); background.setCornerRadius(dp(22));
        background.setStroke(dp(1), Color.rgb(49, 49, 60)); return background;
    }
    private void showAnimatedContent(View content) {
        setContentView(content);
        content.setAlpha(0f);
        content.setTranslationY(dp(12));
        content.animate().alpha(1f).translationY(0f).setDuration(360).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
    }
    private LinearLayout.LayoutParams margin(int l, int t, int r, int b) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.setMargins(dp(l), dp(t), dp(r), dp(b)); return p; }
}
