package pl.rejtag.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.uwb.RangingCapabilities;
import androidx.core.uwb.RangingParameters;
import androidx.core.uwb.RangingPosition;
import androidx.core.uwb.RangingResult;
import androidx.core.uwb.UwbClientSessionScope;
import androidx.core.uwb.UwbComplexChannel;
import androidx.core.uwb.UwbControllerSessionScope;
import androidx.core.uwb.UwbDevice;
import androidx.core.uwb.UwbManager;
import androidx.core.uwb.rxjava3.UwbClientSessionScopeRx;
import androidx.core.uwb.rxjava3.UwbManagerRx;

import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.Locale;

/** Manual, foreground-only UWB ranging experiment; no identifiers or keys go to the server. */
public final class UwbLabActivity extends Activity {
    private static final int INK = Color.rgb(22,31,26), PAPER = Color.rgb(245,245,240), MUTED = Color.rgb(102,113,103);
    private static final int REQUEST_UWB = 41;
    private enum Role { CONTROLLER, CONTROLEE }
    private UwbManager manager;
    private UwbClientSessionScope scope;
    private Disposable pending, ranging;
    private Role role, requestedRole;
    private RangingParameters parameters;
    private String localCode, controllerCode;
    private int epoch;
    private boolean visible, started;
    private long lastMeasurement, sessionStarted;
    private final Handler handler = new Handler();
    private final Runnable monitor = new Runnable() {
        @Override public void run() {
            if (!visible || !started) return;
            long now = SystemClock.elapsedRealtime();
            if (now - (lastMeasurement == 0 ? sessionStarted : lastMeasurement) > 30_000) {
                resetExperiment("Brak aktualnego pomiaru UWB. Spróbuj ponownie lub użyj GPS."); return;
            }
            if (lastMeasurement > 0 && now - lastMeasurement > 3_000) {
                measurement.setText("—"); status.setText("Sygnał UWB chwilowo niedostępny. Czekam na nowy pomiar.");
            }
            handler.postDelayed(this, 1000);
        }
    };
    private TextView status, code, measurement;
    private Button controller, controlee, showCode, enterCode, start, reset;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        getWindow().setStatusBarColor(PAPER);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        ScrollView scroll = new ScrollView(this); scroll.setBackgroundColor(PAPER); setContentView(scroll);
        LinearLayout page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL); page.setPadding(dp(24),dp(22),dp(24),dp(32)); scroll.addView(page);
        TextView back = text("←   WRÓĆ", 13, true); back.setOnClickListener(v -> finish()); add(page, back, 0);
        TextView title = text("UWB\nLaboratorium", 34, true); title.setLetterSpacing(-.04f); add(page, title, 26);
        TextView subtitle = text("POMIAR  /  DWA TELEFONY", 11, true); subtitle.setLetterSpacing(.12f); add(page, subtitle, 8);
        TextView description = text("Eksperymentalne mierzenie odległości między dwoma Androidami. Oba telefony muszą mieć UWB, Android 14 lub nowszy i obsługiwać bezpieczną konfigurację. Użyj tylko za zgodą obu osób.", 15, false); description.setTextColor(MUTED); description.setLineSpacing(dp(3), 1); add(page, description, 18);
        TextView instructions = text("1  Na pierwszym telefonie wybierz B i przepisz jego kod do telefonu A.\n\n2  Na A wybierz A, wprowadź kod B i pokaż drugi kod na ekranie.\n\n3  Przepisz go na B. Naciśnij Start na obu telefonach możliwie szybko.", 14, false); instructions.setLineSpacing(dp(2),1); add(page, instructions, 18);
        status = text("Wybierz rolę, aby sprawdzić możliwości telefonu.", 15, true); status.setBackground(bg(Color.rgb(206,224,202))); status.setPadding(dp(16),dp(16),dp(16),dp(16)); add(page,status,20);
        controller = button("Telefon A · inicjator", () -> choose(Role.CONTROLLER)); add(page,controller,14);
        controlee = button("Telefon B · drugi telefon", () -> choose(Role.CONTROLEE)); add(page,controlee,8);
        showCode = button("Pokaż mój kod", this::displayCode); add(page,showCode,8);
        enterCode = button("Wpisz kod drugiego telefonu", this::inputCode); add(page,enterCode,8);
        start = button("Start pomiaru", this::startRanging); add(page,start,8);
        reset = button("Zatrzymaj i zacznij od nowa", () -> resetExperiment("Pomiar zatrzymany. Wybierz rolę ponownie.")); add(page,reset,8);
        code = text("", 14, true); code.setTextIsSelectable(false); code.setGravity(Gravity.CENTER); add(page,code,16);
        measurement = text("—", 30, true); measurement.setGravity(Gravity.CENTER); add(page,measurement,18);
        TextView warning = text("Kod sesji zawiera klucz. Przepisuj go osobiście i nie wysyłaj publicznie. Kod i pomiar nie są zapisywane ani wysyłane na serwer. Wyjście z ekranu zatrzymuje sesję. To nie jest jeszcze śledzenie AR ani pomiar przez ścianę.", 12, false); warning.setTextColor(MUTED); warning.setLineSpacing(dp(3),1); add(page,warning,14);
        updateButtons();
    }
    @Override protected void onResume() { super.onResume(); visible = true; }
    @Override protected void onPause() { visible = false; resetExperiment("Sesja zakończona po opuszczeniu ekranu. Wybierz rolę ponownie."); super.onPause(); }
    @Override protected void onDestroy() { manager = null; super.onDestroy(); }
    private int dp(int n) { return Math.round(getResources().getDisplayMetrics().density*n); }
    private TextView text(String s, int size, boolean bold) { TextView t = new TextView(this); t.setText(s); t.setTextSize(size); t.setTextColor(INK); if (bold) t.setTypeface(null, Typeface.BOLD); return t; }
    private GradientDrawable bg(int color) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(16)); return d; }
    private void add(LinearLayout p, View v, int margin) { LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-1,-2); l.topMargin=dp(margin); p.addView(v,l); }
    private Button button(String title, Runnable action) { Button b = new Button(this); b.setText(title); b.setAllCaps(false); b.setTextColor(INK); b.setTypeface(null, Typeface.BOLD); b.setBackground(bg(Color.WHITE)); b.setMinHeight(dp(55)); b.setOnClickListener(v -> action.run()); return b; }
    private void updateButtons() {
        boolean prepared = scope != null;
        controller.setEnabled(!started); controlee.setEnabled(!started);
        showCode.setEnabled(prepared && (role == Role.CONTROLEE || controllerCode != null) && !started);
        enterCode.setEnabled(prepared && !started);
        start.setEnabled(prepared && parameters != null && !started);
    }
    private void choose(Role r) {
        resetExperiment("Sprawdzam telefon…");
        if (Build.VERSION.SDK_INT < 34) { status.setText("Ten test używa bezpiecznego UWB i wymaga Androida 14 lub nowszego. Wróć do wizjera GPS."); return; }
        if (!getPackageManager().hasSystemFeature("android.hardware.uwb")) { status.setText("Telefon nie zgłasza UWB. Wróć do wizjera GPS i kompasu."); return; }
        if (checkSelfPermission(Manifest.permission.UWB_RANGING) != PackageManager.PERMISSION_GRANTED) {
            requestedRole = r; requestPermissions(new String[]{Manifest.permission.UWB_RANGING}, REQUEST_UWB); return;
        }
        prepare(r);
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request != REQUEST_UWB) return;
        Role selected = requestedRole; requestedRole = null;
        if (selected != null && checkSelfPermission(Manifest.permission.UWB_RANGING) == PackageManager.PERMISSION_GRANTED) prepare(selected);
        else status.setText("Brak zgody na UWB. Nadal możesz użyć wizjera GPS i kompasu.");
    }
    private void prepare(Role selected) {
        if (!visible) { status.setText("Wróć do ekranu i wybierz rolę ponownie."); return; }
        try {
            if (manager == null) manager = UwbManager.createInstance(getApplicationContext());
            int current = ++epoch;
            role = selected;
            if (selected == Role.CONTROLLER) {
                pending = UwbManagerRx.controllerSessionScopeSingle(manager).subscribeOn(Schedulers.io()).subscribe(
                    result -> runOnUiThread(() -> prepared(current, result)), error -> runOnUiThread(() -> failed(current, error)));
            } else {
                pending = UwbManagerRx.controleeSessionScopeSingle(manager).subscribeOn(Schedulers.io()).subscribe(
                    result -> runOnUiThread(() -> prepared(current, result)), error -> runOnUiThread(() -> failed(current, error)));
            }
        } catch (RuntimeException e) { status.setText("Nie udało się uruchomić UWB. Użyj wizjera GPS."); }
    }
    private void prepared(int current, UwbClientSessionScope result) {
        if (!visible || epoch != current) return;
        try {
        RangingCapabilities cap = result.getRangingCapabilities();
        if (!cap.isDistanceSupported() || !cap.getSupportedConfigIds().contains(RangingParameters.CONFIG_PROVISIONED_UNICAST_DS_TWR)) {
            resetExperiment("Telefon nie obsługuje wymaganego bezpiecznego pomiaru UWB. Użyj wizjera GPS."); return;
        }
        byte[] address = result.getLocalAddress().getAddress();
        if (address == null || address.length != 2) { resetExperiment("Nieobsługiwany format adresu UWB. Użyj wizjera GPS."); return; }
        scope = result;
        localCode = UwbProtocol.peerCode(address);
        status.setText(role == Role.CONTROLEE ? "Telefon B gotowy. Pokaż kod i przepisz go na A." : "Telefon A gotowy. Wpisz kod z telefonu B.");
        updateButtons();
        } catch (RuntimeException e) { resetExperiment("Nie udało się odczytać możliwości UWB. Użyj wizjera GPS."); }
    }
    private void failed(int current, Throwable error) {
        if (epoch != current || !visible) return;
        resetExperiment("Usługa UWB jest niedostępna. Sprawdź ustawienia i usługi Google Play lub wróć do wizjera GPS.");
    }
    private void displayCode() {
        String value = role == Role.CONTROLEE ? localCode : controllerCode;
        if (value == null) return;
        code.setText(value);
        status.setText("Przepisz ten kod na drugim telefonie. Nie pokazuj go osobom postronnym.");
    }
    private void inputCode() {
        if (scope == null || started) return;
        EditText input = new EditText(this); input.setSingleLine(false); input.setHint(role == Role.CONTROLLER ? "RTUWB1:P:…" : "RTUWB1:C:…");
        new AlertDialog.Builder(this).setTitle("Kod drugiego telefonu").setMessage("Przepisz kod z ekranu drugiego telefonu. Nie używaj publicznego czatu.").setView(input).setNegativeButton("Anuluj",null).setPositiveButton("Dalej", (d,w) -> acceptCode(input.getText().toString().trim())).show();
    }
    private void acceptCode(String raw) {
        if (scope == null || started) return;
        try {
            if (role == Role.CONTROLLER) {
                byte[] peer = UwbProtocol.parsePeer(raw);
                if (java.util.Arrays.equals(peer, scope.getLocalAddress().getAddress())) throw new IllegalArgumentException();
                UwbComplexChannel channel = ((UwbControllerSessionScope) scope).getUwbComplexChannel();
                if (channel == null) throw new IllegalArgumentException();
                SecureRandom random = new SecureRandom(); byte[] key = new byte[16]; random.nextBytes(key);
                int sid = random.nextInt(Integer.MAX_VALUE - 1) + 1;
                parameters = params(peer, channel, sid, key);
                controllerCode = UwbProtocol.configCode(scope.getLocalAddress().getAddress(), channel.getChannel(), channel.getPreambleIndex(), sid, key);
                code.setText(""); status.setText("Kod B przyjęty. Pokaż kod A na drugim telefonie, potem uruchom pomiar na obu.");
            } else {
                UwbProtocol.Config config = UwbProtocol.parseConfig(raw);
                if (java.util.Arrays.equals(config.peer, scope.getLocalAddress().getAddress()) || !scope.getRangingCapabilities().getSupportedChannels().contains(config.channel)) throw new IllegalArgumentException();
                parameters = params(config.peer, new UwbComplexChannel(config.channel, config.preamble), config.sessionId, config.key);
                code.setText(""); status.setText("Kod A przyjęty. Uruchom pomiar na obu telefonach.");
            }
            updateButtons();
        } catch (RuntimeException e) { parameters = null; controllerCode = null; code.setText(""); status.setText("Kod jest nieprawidłowy lub telefon nie obsługuje tego kanału. Sprawdź kod albo użyj GPS."); updateButtons(); }
    }
    private RangingParameters params(byte[] peer, UwbComplexChannel channel, int sid, byte[] key) {
        return new RangingParameters(RangingParameters.CONFIG_PROVISIONED_UNICAST_DS_TWR, sid, 0, key, null, channel,
            Collections.singletonList(UwbDevice.createForAddress(peer)), RangingParameters.RANGING_UPDATE_RATE_AUTOMATIC);
    }
    private void startRanging() {
        if (scope == null || parameters == null || started) return;
        final int current = epoch; started = true; lastMeasurement = 0; sessionStarted = SystemClock.elapsedRealtime();
        handler.removeCallbacks(monitor); handler.postDelayed(monitor, 1000); code.setText(""); updateButtons();
        status.setText("Oczekiwanie na drugi telefon. Uruchom pomiar na obu urządzeniach.");
        try {
            ranging = UwbClientSessionScopeRx.rangingResultsObservable(scope, parameters).subscribeOn(Schedulers.io()).subscribe(
                result -> runOnUiThread(() -> onResult(current,result)),
                error -> runOnUiThread(() -> failed(current,error)),
                () -> runOnUiThread(() -> { if (visible && epoch == current) resetExperiment("Pomiar zakończony. Wybierz rolę ponownie."); }));
        } catch (RuntimeException e) { failed(current,e); }
    }
    private void onResult(int current, RangingResult result) {
        if (!visible || epoch != current) return;
        if (result instanceof RangingResult.RangingResultPosition) {
            RangingPosition pos = ((RangingResult.RangingResultPosition) result).getPosition();
            long sampleAge = SystemClock.elapsedRealtimeNanos() - pos.getElapsedRealtimeNanos();
            if (sampleAge >= 0 && sampleAge < 3_000_000_000L && pos.getDistance() != null && Float.isFinite(pos.getDistance().getValue()) && pos.getDistance().getValue() >= 0) {
                lastMeasurement = SystemClock.elapsedRealtime();
                String value = String.format(Locale.getDefault(), "%.2f m", pos.getDistance().getValue());
                if (pos.getAzimuth() != null && Float.isFinite(pos.getAzimuth().getValue())) value += String.format(Locale.getDefault(), "\nKąt poziomy: %.0f°", pos.getAzimuth().getValue());
                measurement.setText(value); status.setText("Pomiar UWB · wartości zależą od warunków i telefonu.");
            }
        } else if (result instanceof RangingResult.RangingResultPeerDisconnected) resetExperiment("Utracono drugi telefon. Przygotuj nowe kody albo użyj GPS.");
    }
    private void resetExperiment(String message) {
        handler.removeCallbacks(monitor); lastMeasurement = 0; sessionStarted = 0;
        epoch++; if (pending != null) { pending.dispose(); pending = null; } if (ranging != null) { ranging.dispose(); ranging = null; }
        scope = null; role = null; parameters = null; localCode = null; controllerCode = null; started = false;
        if (status != null) status.setText(message); if (code != null) code.setText(""); if (measurement != null) measurement.setText("—");
        if (controller != null) updateButtons();
    }
}
