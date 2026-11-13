package pl.rejtag.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.os.SystemClock;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Camera;
import android.hardware.GeomagneticField;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.text.InputType;
import org.json.JSONArray;
import org.json.JSONObject;
import java.time.Instant;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A deliberately honest first slice: camera + compass + explicitly shared GPS snapshots. */
public class MainActivity extends Activity implements SensorEventListener, LocationListener {
    private static final int INK = Color.rgb(22, 31, 26), MUTED = Color.rgb(102, 113, 103), PAPER = Color.rgb(245, 245, 240), GREEN = Color.rgb(206, 224, 202);
    private static final long MAX_AGE_MS = 5 * 60 * 1000L;
    private static final Pattern CODE = Pattern.compile("REJTAG1:([-+0-9.]+),([-+0-9.]+),([0-9]+),([0-9.]+)");
    private LocationManager locationManager;
    private SensorManager sensorManager;
    private Sensor rotationSensor, accelerometer, magnetometer;
    private float[] gravity, magnetic;
    private long gravityTime, magneticTime, rotationTime;
    private int magneticAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE;
    private float rotationHeading = Float.NaN, magneticHeading = Float.NaN;
    private boolean rotationVertical, magneticVertical;
    private Location own, target;
    private float heading = Float.NaN;
    private boolean cameraTooVertical = false;
    private int compassAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE;
    private boolean finder = false;
    private TextView locationLabel, targetLabel, finderInfo, finderArrow, cameraLabel, liveLabel;
    private String cameraStatus = "Przygotowuję kamerę…";
    private final LiveClient live = new LiveClient();
    private boolean foreground, liveTickRunning;
    private int liveEpoch;
    private JSONArray friends = new JSONArray();
    private String liveFriend, liveStatus = "";
    private Camera camera;
    private TextureView texture;
    private final android.os.Handler handler = new android.os.Handler();
    private final Runnable liveTick = this::refreshLive;
    private final Runnable refresh = new Runnable() { public void run() { updateLabels(); handler.postDelayed(this, 1000); } };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(PAPER);
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        if (rotationSensor == null) rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR);
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        showHome();
    }
    @Override protected void onResume() {
        super.onResume();
        foreground = true; live.setForeground(true);
        scheduleLive(0);
        if (rotationSensor != null) sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_UI);
        if (accelerometer != null && magnetometer != null) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI);
            sensorManager.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_UI);
        }
        startLocation();
        handler.post(refresh);
        if (finder && texture != null && texture.isAvailable()) openCamera(texture.getSurfaceTexture());
    }
    @Override protected void onPause() {
        super.onPause();
        foreground = false; live.setForeground(false); liveEpoch++; liveTickRunning = false;
        handler.removeCallbacks(liveTick);
        handler.removeCallbacks(refresh);
        sensorManager.unregisterListener(this);
        rotationHeading = magneticHeading = Float.NaN;
        gravity = magnetic = null;
        locationManager.removeUpdates(this);
        releaseCamera();
    }
    @Override protected void onDestroy() { handler.removeCallbacks(liveTick); live.close(); super.onDestroy(); }
    private boolean hasLocation() { return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED; }
    private void startLocation() {
        if (!hasLocation()) { updateLabels(); return; }
        locationManager.removeUpdates(this);
        boolean fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        for (String provider : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
            // Approximate permission can use the network provider but not the GPS provider.
            if (provider.equals(LocationManager.GPS_PROVIDER) && !fine) continue;
            try {
                if (locationManager.isProviderEnabled(provider)) {
                    Location last = locationManager.getLastKnownLocation(provider);
                    if (last != null && (own == null || last.getTime() > own.getTime())) own = last;
                    locationManager.requestLocationUpdates(provider, 2000, 0, this);
                }
            } catch (SecurityException | IllegalArgumentException ignored) {
                // Some devices do not implement every provider; keep trying the others.
            }
        }
        updateLabels();
    }
    @Override public void onLocationChanged(Location l) { if (own == null || l.getTime() >= own.getTime()) own = l; updateLabels(); }
    @Override public void onProviderEnabled(String p) { updateLabels(); }
    @Override public void onProviderDisabled(String p) { updateLabels(); }
    @Override public void onStatusChanged(String p, int s, Bundle b) { }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == 10) startLocation();
        if (request == 11 && finder) {
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                cameraStatus = "Bez kamery · działa tryb orientacyjny";
            } else if (texture != null && texture.isAvailable()) openCamera(texture.getSurfaceTexture());
            updateLabels();
        }
    }
    private void requestLocation() {
        if (!hasLocation()) requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, 10);
        else { startLocation(); toast("Szukam aktualnej pozycji…"); }
    }
    @Override public void onSensorChanged(SensorEvent event) {
        int type = event.sensor.getType();
        if (event.sensor == rotationSensor) {
            float[] matrix = new float[9];
            SensorManager.getRotationMatrixFromVector(matrix, event.values);
            rotationHeading = headingFromMatrix(matrix);
            rotationVertical = Math.abs(matrix[8]) > 0.85f;
            rotationTime = event.timestamp;
            compassAccuracy = event.accuracy;
        } else if (type == Sensor.TYPE_ACCELEROMETER) {
            gravity = event.values.clone(); gravityTime = event.timestamp;
        } else if (type == Sensor.TYPE_MAGNETIC_FIELD) {
            magnetic = event.values.clone(); magneticTime = event.timestamp;
            magneticAccuracy = event.accuracy;
        }
        if ((type == Sensor.TYPE_ACCELEROMETER || type == Sensor.TYPE_MAGNETIC_FIELD)
                && gravity != null && magnetic != null) {
            float[] matrix = new float[9];
            if (SensorManager.getRotationMatrix(matrix, null, gravity, magnetic)) {
                magneticHeading = headingFromMatrix(matrix);
                magneticVertical = Math.abs(matrix[8]) > 0.85f;
            } else magneticHeading = Float.NaN;
        }
    }
    private float headingFromMatrix(float[] matrix) {
        float[] adjusted = new float[9], orientation = new float[3];
        // The rear camera points along negative Z in portrait.
        if (!SensorManager.remapCoordinateSystem(matrix, SensorManager.AXIS_X, SensorManager.AXIS_Z, adjusted)) return Float.NaN;
        SensorManager.getOrientation(adjusted, orientation);
        float declination = own == null ? 0 : new GeomagneticField((float) own.getLatitude(), (float) own.getLongitude(), (float) own.getAltitude(), System.currentTimeMillis()).getDeclination();
        return (float) ((Math.toDegrees(orientation[0]) + 180 + declination + 360) % 360);
    }
    private boolean recentSensor(long time) {
        return time > 0 && SystemClock.elapsedRealtimeNanos() - time < 5_000_000_000L;
    }
    private boolean chooseHeading() {
        if (!Float.isNaN(rotationHeading) && compassAccuracy > SensorManager.SENSOR_STATUS_UNRELIABLE && recentSensor(rotationTime)) {
            heading = rotationHeading; cameraTooVertical = rotationVertical; return true;
        }
        if (!Float.isNaN(magneticHeading) && magneticAccuracy > SensorManager.SENSOR_STATUS_UNRELIABLE && recentSensor(gravityTime) && recentSensor(magneticTime)) {
            heading = magneticHeading; cameraTooVertical = magneticVertical; return true;
        }
        heading = Float.NaN;
        return false;
    }
    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {
        if (sensor == rotationSensor) compassAccuracy = accuracy;
        if (sensor == magnetometer) magneticAccuracy = accuracy;
    }

    private JSONObject params(Object... pairs) {
        JSONObject obj = new JSONObject();
        try { for (int i = 0; i < pairs.length; i += 2) obj.put((String) pairs[i], pairs[i + 1]); }
        catch (Exception e) { throw new IllegalArgumentException(e); }
        return obj;
    }
    private void scheduleLive(long delay) {
        handler.removeCallbacks(liveTick);
        if (foreground && live.signedIn()) handler.postDelayed(liveTick, delay);
    }
    private boolean tickValid(int epoch) { return foreground && live.signedIn() && epoch == liveEpoch; }
    private void finishTick(int epoch) {
        if (!tickValid(epoch)) return;
        liveTickRunning = false; updateLabels(); scheduleLive(15_000);
    }
    private void refreshLive() {
        if (!foreground || !live.signedIn() || liveTickRunning) return;
        liveTickRunning = true;
        int epoch = liveEpoch;
        live.rpc("rejtag_friends", params(), (answer, error) -> {
            if (!tickValid(epoch)) return;
            if (error != null || !(answer instanceof JSONArray)) {
                liveStatus = error == null ? "nieprawidłowa odpowiedź" : error;
                if (liveFriend != null) target = null;
                finishTick(epoch); return;
            }
            friends = (JSONArray) answer;
            boolean sharing = false;
            for (int i = 0; i < friends.length(); i++) {
                JSONObject item = friends.optJSONObject(i);
                if (item != null && item.optBoolean("sharing_with_friend")) sharing = true;
            }
            liveStatus = "połączono";
            if (sharing && hasLocation() && own != null && own.hasAccuracy() && System.currentTimeMillis() - own.getTime() >= -30_000 && System.currentTimeMillis() - own.getTime() < 120_000) {
                JSONObject data = params("p_lat", own.getLatitude(), "p_lon", own.getLongitude(), "p_accuracy", own.getAccuracy(), "p_fix_at", Instant.ofEpochMilli(own.getTime()).toString());
                live.rpc("rejtag_publish", data, (ignored, publishError) -> {
                    if (!tickValid(epoch)) return;
                    if (publishError != null) liveStatus = "nie wysłano pozycji: " + publishError;
                    readLiveTarget(epoch);
                });
            } else {
                if (sharing) liveStatus = "udostępnianie włączone, czekam na aktualną lokalizację";
                readLiveTarget(epoch);
            }
        });
    }
    private void readLiveTarget(int epoch) {
        final String selected = liveFriend;
        if (selected == null) { finishTick(epoch); return; }
        live.rpc("rejtag_read_position", params("p_friend", selected), (answer, error) -> {
            if (!tickValid(epoch)) return;
            if (!selected.equals(liveFriend)) { finishTick(epoch); return; }
            target = null;
            if (error != null || !(answer instanceof JSONArray)) {
                liveStatus = error == null ? "brak danych" : error;
            } else {
                JSONArray rows = (JSONArray) answer;
                JSONObject row = rows.optJSONObject(0);
                if (row != null) try {
                    double lat = row.getDouble("lat"), lon = row.getDouble("lon");
                    float accuracy = (float) row.getDouble("accuracy");
                    long time = Instant.parse(row.getString("fix_at")).toEpochMilli();
                    if (!Double.isFinite(lat) || !Double.isFinite(lon) || !Float.isFinite(accuracy) || lat < -90 || lat > 90 || lon < -180 || lon > 180 || accuracy < 0 || accuracy > 100000) throw new IllegalArgumentException();
                    Location incoming = new Location("live"); incoming.setLatitude(lat); incoming.setLongitude(lon); incoming.setAccuracy(accuracy); incoming.setTime(time);
                    if (time <= System.currentTimeMillis() + 30_000 && System.currentTimeMillis() - time <= 120_000) target = incoming;
                    else liveStatus = "pomiar jest nieaktualny";
                } catch (Exception e) { liveStatus = "nieprawidłowy pomiar"; }
                else liveStatus = "znajomy nie udostępnia pozycji lub brak aktualnych danych";
            }
            finishTick(epoch);
        });
    }
    private void showLiveMenu() {
        if (!live.configured()) { new AlertDialog.Builder(this).setTitle("Tryb na żywo jest nieaktywny").setMessage("Ten build nie ma skonfigurowanej usługi. Kod ręczny i wizjer nadal są dostępne. Szczegóły konfiguracji są w README.").setPositiveButton("OK", null).show(); return; }
        if (!live.signedIn()) { showLogin(); return; }
        String[] options = {"Moi znajomi", "Wyślij zaproszenie", "Przyjmij zaproszenie", "Zatrzymaj wszystkie udostępnienia", "Wyloguj"};
        new AlertDialog.Builder(this).setTitle("Znajomi · tryb na żywo").setMessage("Lokalizacja aktualizuje się tylko, gdy aplikacja jest otwarta. Udostępnianie włącza się osobno dla każdego znajomego.").setItems(options, (d, which) -> {
            if (which == 0) loadFriends();
            else if (which == 1) inviteFriend();
            else if (which == 2) acceptFriend();
            else if (which == 3) stopAll();
            else new AlertDialog.Builder(this).setTitle("Wylogować?").setMessage("Wylogowanie zatrzyma Twoje udostępnianie i zakończy sesję RejTag. Inne sesje RejHub pozostaną aktywne. Potrzebne jest połączenie z internetem.").setNegativeButton("Anuluj", null).setPositiveButton("Wyloguj", (x, w) -> logout()).show();
        }).show();
    }
    private void logout() {
        liveEpoch++; liveTickRunning = false; handler.removeCallbacks(liveTick);
        live.rpc("rejtag_logout", params(), (response, error) -> {
            if (error != null) {
                new AlertDialog.Builder(this).setTitle("Nie potwierdzono wylogowania")
                    .setMessage("Nie udało się skontaktować z serwerem lub sesja wygasła. Możesz usunąć sesję z tego urządzenia, ale udostępnianie na serwerze może pozostać włączone. Aby je wyłączyć, zaloguj się ponownie i wybierz «Zatrzymaj wszystkie udostępnienia».")
                    .setNegativeButton("Wróć", (d, w) -> scheduleLive(0))
                    .setPositiveButton("Usuń sesję lokalnie", (d, w) -> { clearLocalSession(); toast("Sesja usunięta lokalnie. Nie potwierdzono wyłączenia udostępniania."); }).show();
                return;
            }
            clearLocalSession(); toast("Wylogowano i zatrzymano udostępnianie.");
        });
    }
    private void clearLocalSession() {
        live.clear(); friends = new JSONArray(); liveFriend = null; target = null; liveStatus = "";
        handler.removeCallbacks(liveTick); showHome();
    }
    private void showLogin() {
        LinearLayout fields = column(); fields.setPadding(dp(24), 0, dp(24), 0);
        EditText name = new EditText(this); name.setHint("Nazwa konta RejHub"); name.setSingleLine(true); name.setInputType(InputType.TYPE_CLASS_TEXT); fields.addView(name);
        EditText password = new EditText(this); password.setHint("Hasło lub dotychczasowy PIN"); password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD); fields.addView(password);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Zaloguj się do RejHub")
            .setMessage("Użyj tej samej nazwy i hasła co na RejHub. Sesja jest tylko w pamięci aplikacji; udostępnianie pozycji wymaga osobnej zgody.")
            .setView(fields).setNegativeButton("Anuluj", null).setNeutralButton("Załóż konto", null).setPositiveButton("Zaloguj", null).create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> authenticate(name.getText().toString().trim(), password.getText().toString(), dialog));
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://rejhub.vercel.app/logowanie.html"))); dialog.dismiss(); }
                catch (android.content.ActivityNotFoundException e) { toast("Otwórz rejhub.vercel.app/logowanie.html w przeglądarce."); }
            });
        });
        dialog.show();
    }
    private void authenticate(String name, String password, AlertDialog dialog) {
        if (!name.matches("^[A-Za-z0-9][A-Za-z0-9_.-]{2,23}$") || password.isEmpty() || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) {
            toast("Wpisz poprawną nazwę konta RejHub i hasło lub PIN."); return;
        }
        dialog.setCancelable(false); dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false); dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false); dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(false);
        live.signIn(name, password, (response, error) -> {
            if (error != null) { toast(error); dialog.setCancelable(true); dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(true); dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true); dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(true); return; }
            dialog.dismiss(); liveStatus = "połączono"; scheduleLive(0); showHome(); toast("Zalogowano jako " + live.username() + ".");
        });
    }
    private void loadFriends() {
        live.rpc("rejtag_friends", params(), (answer, error) -> {
            if (error != null || !(answer instanceof JSONArray)) { toast(error == null ? "Nie udało się pobrać znajomych." : error); return; }
            final JSONArray displayedFriends = (JSONArray) answer;
            friends = displayedFriends;
            if (displayedFriends.length() == 0) { toast("Brak znajomych. Wyślij lub przyjmij zaproszenie."); return; }
            String[] items = new String[displayedFriends.length()];
            for (int i = 0; i < displayedFriends.length(); i++) {
                JSONObject f = displayedFriends.optJSONObject(i); String id = f == null ? "?" : f.optString("friend_id", "?");
                String friendName = f == null ? "Znajomy" : f.optString("username", "Znajomy");
                items[i] = friendName + " · " + id + "\nTy: " + (f != null && f.optBoolean("sharing_with_friend") ? "udostępniasz" : "nie udostępniasz") + " · znajomy: " + (f != null && f.optBoolean("friend_sharing") ? "udostępnia" : "nie udostępnia");
            }
            new AlertDialog.Builder(this).setTitle("Znajomi RejHub").setItems(items, (d, which) -> friendActions(displayedFriends.optJSONObject(which))).setNegativeButton("Zamknij", null).show();
        });
    }
    private void friendActions(JSONObject friend) {
        if (friend == null) return;
        String id = friend.optString("friend_id"); boolean sharing = friend.optBoolean("sharing_with_friend");
        String[] options = {"Pokaż pozycję znajomego", sharing ? "Wyłącz moje udostępnianie" : "Udostępnij mu moją pozycję", "Usuń znajomego"};
        new AlertDialog.Builder(this).setTitle(friend.optString("username", "Znajomy") + " · " + id).setItems(options, (d, which) -> {
            if (which == 0) { liveFriend = id; target = null; liveStatus = "oczekiwanie na pozycję"; scheduleLive(0); showHome(); }
            else if (which == 1) {
                if (!sharing) new AlertDialog.Builder(this).setTitle("Udostępnić pozycję?").setMessage("Ten znajomy będzie mógł widzieć Twoją aktualną pozycję, gdy aplikacja będzie otwarta. Zgoda pozostaje aktywna do wyłączenia. Możesz ją w każdej chwili cofnąć.").setNegativeButton("Anuluj", null).setPositiveButton("Udostępnij", (x, w) -> setSharing(id, true)).show();
                else setSharing(id, false);
            } else new AlertDialog.Builder(this).setTitle("Usunąć znajomego?").setMessage("Udostępnianie w obie strony zostanie wyłączone.").setNegativeButton("Anuluj", null).setPositiveButton("Usuń", (x, w) -> live.rpc("rejtag_remove_friend", params("p_friend", id), (r, err) -> { if (err != null) toast(err); else { if (id.equals(liveFriend)) { liveFriend = null; target = null; } toast("Znajomy usunięty."); scheduleLive(0); showHome(); } })).show();
        }).setNegativeButton("Wróć", null).show();
    }
    private void setSharing(String id, boolean enabled) {
        live.rpc("rejtag_set_sharing", params("p_friend", id, "p_enabled", enabled), (r, error) -> {
            if (error != null) { toast(error); return; }
            toast(enabled ? "Udostępnianie włączone." : "Udostępnianie zatrzymane."); scheduleLive(0); showHome();
        });
    }
    private void stopAll() {
        new AlertDialog.Builder(this).setTitle("Zatrzymać udostępnianie?").setMessage("Wszyscy znajomi stracą dostęp do Twojej pozycji. Niewykorzystane zaproszenia również zostaną unieważnione.").setNegativeButton("Anuluj", null).setPositiveButton("Zatrzymaj", (d, w) -> live.rpc("rejtag_stop_all", params(), (r, error) -> {
            if (error != null) { toast("Nie potwierdzono zatrzymania: " + error); return; }
            toast("Udostępnianie zatrzymane."); scheduleLive(0); showHome();
        })).show();
    }
    private void inviteFriend() {
        new AlertDialog.Builder(this).setTitle("Zaproszenie do znajomych").setMessage("Wyślij kod tylko zaufanej osobie. Każdy, kto go otrzyma, może go użyć raz przez 24 godziny. Samo dodanie nie włącza udostępniania pozycji.").setNegativeButton("Anuluj", null).setPositiveButton("Utwórz", (d, w) -> live.rpc("rejtag_invite", params(), (response, error) -> {
            if (error != null || !(response instanceof String)) { toast(error == null ? "Nie udało się utworzyć zaproszenia." : error); return; }
            Intent send = new Intent(Intent.ACTION_SEND); send.setType("text/plain"); send.putExtra(Intent.EXTRA_TEXT, "Zaproszenie RejTag: REJFRIEND1:" + response); startActivity(Intent.createChooser(send, "Wyślij zaproszenie"));
        })).show();
    }
    private void acceptFriend() {
        EditText input = new EditText(this); input.setHint("Wklej zaproszenie REJFRIEND1…");
        new AlertDialog.Builder(this).setTitle("Przyjmij zaproszenie").setView(input).setNegativeButton("Anuluj", null).setPositiveButton("Przyjmij", (d, w) -> {
            Matcher match = Pattern.compile("REJFRIEND1:([0-9a-f]{64})").matcher(input.getText().toString());
            if (!match.find()) { toast("Nieprawidłowe zaproszenie."); return; }
            live.rpc("rejtag_accept_invite", params("p_token", match.group(1)), (response, error) -> { if (error != null) toast(error); else { toast("Dodano znajomego. Udostępnianie jest wyłączone."); scheduleLive(0); } });
        }).show();
    }

    private int dp(float n) { return Math.round(getResources().getDisplayMetrics().density * n); }
    private GradientDrawable bg(int color, int radius) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d; }
    private TextView text(String value, int size, int color, boolean bold) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color); t.setFontFeatureSettings("kern");
        if (bold) t.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        return t;
    }
    private LinearLayout column() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private void add(LinearLayout parent, View child, int top) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = dp(top); parent.addView(child, p); }
    private Button button(String label, boolean primary, Runnable action) {
        Button b = new Button(this); b.setText(label); b.setTextSize(15); b.setAllCaps(false); b.setTypeface(null, Typeface.BOLD); b.setTextColor(primary ? Color.WHITE : INK);
        b.setBackground(bg(primary ? INK : Color.WHITE, 16)); b.setMinHeight(dp(56)); b.setOnClickListener(v -> action.run()); return b;
    }
    private TextView tag(String value) { TextView t = text(value, 11, INK, true); t.setLetterSpacing(.12f); return t; }
    private void showHome() {
        finder = false; releaseCamera(); texture = null;
        getWindow().setStatusBarColor(PAPER); getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(PAPER);
        LinearLayout page = column(); page.setPadding(dp(24), dp(22), dp(24), dp(32)); scroll.addView(page); setContentView(scroll);
        TextView brand = text("rejtag  /  LAB 01", 16, INK, true); brand.setLetterSpacing(.06f); add(page, brand, 0);
        add(page, tag("BLISKO, NAWET GDY NIE WIDAĆ"), 46);
        TextView headline = text("Znajdź swoją\nekipę.", 40, INK, true); headline.setLetterSpacing(-.04f); headline.setLineSpacing(dp(-2), 1f); add(page, headline, 12);
        TextView intro = text("Mniej szukania. Więcej spotkań.\nTwój pierwszy krok w stronę przestrzennego RejTag.", 15, MUTED, false); intro.setLineSpacing(dp(3), 1f); add(page, intro, 16);
        LinearLayout card = column(); card.setPadding(dp(20), dp(20), dp(20), dp(20)); card.setBackground(bg(GREEN, 22)); add(page, card, 30);
        add(card, tag("01  /  TWOJA POZYCJA"), 0);
        locationLabel = text("Lokalizacja nie jest jeszcze dostępna", 17, INK, true); add(card, locationLabel, 14);
        TextView locNote = text("Lokalizacja jest pobierana tylko przy otwartej aplikacji. Wysyłasz ją ręcznie lub wybranemu znajomemu w trybie na żywo.", 13, INK, false); locNote.setLineSpacing(dp(3), 1); add(card, locNote, 8);
        add(card, button("Włącz lokalizację", false, this::requestLocation), 16);
        add(card, button("Wyślij moją pozycję  ↗", true, this::shareLocation), 8);
        LinearLayout friend = column(); friend.setPadding(dp(20), dp(20), dp(20), dp(20)); friend.setBackground(bg(Color.WHITE, 22)); add(page, friend, 12);
        add(friend, tag("02  /  TWÓJ ZNAJOMY"), 0);
        targetLabel = text("Czeka na kod od znajomego", 17, INK, true); add(friend, targetLabel, 14);
        TextView friendNote = text("Poproś znajomego o kod RejTag i wklej go tutaj. To pojedyncza pozycja, nie śledzenie na żywo.", 13, MUTED, false); friendNote.setLineSpacing(dp(3), 1); add(friend, friendNote, 8);
        add(friend, button("Wklej kod znajomego  +", false, this::inputTarget), 16);
        add(page, button(live.signedIn() ? "Znajomi i udostępnianie  ↗" : "Połącz ze znajomym  ↗", false, this::showLiveMenu), 16);
        liveLabel = text("", 12, MUTED, false); add(page, liveLabel, 8);
        add(page, button("Otwórz wizjer  ↗", true, this::showFinder), 16);
        add(page, button("Otwórz pozycję na mapie  ↗", false, this::openMap), 8);
        add(page, button("Pokaż współrzędne", false, this::showCoordinates), 8);
        add(page, button("Możliwości telefonu  +", false, this::showCapabilities), 8);
        add(page, button("Laboratorium UWB  ↗", false, this::openUwbLab), 8);
        TextView foot = text("WERSJA EKSPERYMENTALNA  ·  GPS + KOMPAS", 10, MUTED, true); foot.setLetterSpacing(.08f); foot.setGravity(Gravity.CENTER); add(page, foot, 25);
        updateLabels();
    }
    private boolean fresh(Location l) {
        long limit = l != null && l == target && liveFriend != null ? 120_000L : MAX_AGE_MS;
        return l != null && l.getTime() <= System.currentTimeMillis() + 60_000 && System.currentTimeMillis() - l.getTime() <= limit;
    }
    private String age(Location l) { long seconds = Math.max(0, (System.currentTimeMillis() - l.getTime()) / 1000); return seconds < 60 ? seconds + " s temu" : seconds / 60 + " min temu"; }
    private void shareLocation() {
        if (!hasLocation()) { requestLocation(); return; }
        if (!fresh(own) || !own.hasAccuracy()) { toast("Poczekaj na aktualną pozycję GPS i jej dokładność."); return; }
        String code = String.format(Locale.US, "REJTAG1:%.6f,%.6f,%d,%.1f", own.getLatitude(), own.getLongitude(), own.getTime(), own.getAccuracy());
        Intent send = new Intent(Intent.ACTION_SEND); send.setType("text/plain"); send.putExtra(Intent.EXTRA_TEXT, "Moja jednorazowa pozycja RejTag (aplikacja przestanie przyjmować kod po 5 minutach; współrzędne można zachować):\n" + code); startActivity(Intent.createChooser(send, "Wyślij pozycję znajomemu"));
    }
    private void inputTarget() {
        EditText input = new EditText(this); input.setSingleLine(false); input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE); input.setHint("Wklej wiadomość z kodem REJTAG1…");
        new AlertDialog.Builder(this).setTitle("Pozycja znajomego").setMessage("Wklej kod, który znajomy dobrowolnie Ci wysłał.").setView(input).setNegativeButton("Anuluj", null).setPositiveButton("Dodaj", (dialog, which) -> {
            Matcher m = CODE.matcher(input.getText().toString());
            try {
                if (!m.find()) throw new IllegalArgumentException();
                double lat = Double.parseDouble(m.group(1)), lon = Double.parseDouble(m.group(2)); long time = Long.parseLong(m.group(3)); float accuracy = Float.parseFloat(m.group(4));
                if (!Double.isFinite(lat) || !Double.isFinite(lon) || lat < -90 || lat > 90 || lon < -180 || lon > 180 || !Float.isFinite(accuracy) || accuracy < 0 || accuracy > 100000) throw new IllegalArgumentException();
                Location parsed = new Location("shared"); parsed.setLatitude(lat); parsed.setLongitude(lon); parsed.setTime(time); parsed.setAccuracy(accuracy);
                if (!fresh(parsed)) { toast("Ten kod wygasł lub ma nieprawidłową datę. Poproś o nowy."); return; }
                liveFriend = null; target = parsed; updateLabels(); toast("Dodano pozycję znajomego.");
            } catch (Exception e) { toast("Nieprawidłowy kod RejTag."); }
        }).show();
    }
    private void updateLabels() {
        if (!finder) {
            if (liveLabel != null) liveLabel.setText(live.signedIn() ? "Konto " + live.username() + " · na żywo: " + (liveStatus.isEmpty() ? "połączono" : liveStatus) + ". Aktualizacje tylko przy otwartej aplikacji." : "Tryb na żywo wymaga konta i skonfigurowanej usługi. Ręczny kod działa bez konta.");
            if (locationLabel != null) {
                if (!hasLocation()) locationLabel.setText("Włącz dostęp do lokalizacji");
                else if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) && !locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) locationLabel.setText("Włącz lokalizację w ustawieniach telefonu");
                else locationLabel.setText(own == null ? "Czekamy na lokalizację…" : (fresh(own) ? "Pozycja gotowa" : "Pozycja nieaktualna") + " · " + age(own));
            }
            if (targetLabel != null) targetLabel.setText(target == null ? (liveFriend == null ? "Czeka na kod od znajomego" : "Na żywo · " + liveStatus) : fresh(target) ? (liveFriend == null ? "Pozycja dodana · " : "Na żywo · ") + age(target) : (liveFriend == null ? "Kod wygasł · poproś o nowy" : "Pozycja na żywo nieaktualna"));
            return;
        }
        if (finderInfo == null || finderArrow == null) return;
        if (cameraLabel != null) cameraLabel.setText(cameraStatus);
        if (!hasLocation()) { finderArrow.setText("—"); finderInfo.setText("Brak dostępu do lokalizacji. Wróć i włącz go w aplikacji lub ustawieniach telefonu. Możesz otworzyć pozycję znajomego na mapie."); return; }
        if (!fresh(own)) { finderArrow.setText("—"); finderInfo.setText("Czekamy na Twoją aktualną lokalizację. Włącz GPS lub użyj mapy pozycji znajomego."); return; }
        if (!fresh(target)) { finderArrow.setText("—"); finderInfo.setText(target == null ? (liveFriend == null ? "Dodaj kod znajomego na ekranie głównym." : "Brak aktualnej pozycji na żywo: " + liveStatus) : (liveFriend == null ? "Pozycja znajomego wygasła. Poproś o nowy kod." : "Pozycja na żywo jest nieaktualna.")); return; }
        float distance = own.distanceTo(target);
        float uncertainty = (own.hasAccuracy() ? own.getAccuracy() : 0) + (target.hasAccuracy() ? target.getAccuracy() : 0);
        String summary = String.format(Locale.getDefault(), "Około %.0f m w linii prostej\nSzacowana niepewność lokalizacji: około %.0f m  ·  pomiar: %s", distance, uncertainty, age(target));
        if (!own.hasAccuracy() || !target.hasAccuracy() || distance <= uncertainty) {
            finderArrow.setText("—"); finderInfo.setText(summary + "\nZbyt mała dokładność GPS, aby wskazać kierunek. Spróbuj mapy lub popraw sygnał."); return;
        }
        if (!chooseHeading()) {
            finderArrow.setText("—"); finderInfo.setText(summary + "\nKompas niedostępny lub nieskalibrowany. Porusz telefonem w ósemkę albo użyj mapy."); return;
        }
        if (cameraTooVertical) { finderArrow.setText("—"); finderInfo.setText(summary + "\nTrzymaj telefon pionowo, z kamerą skierowaną przed siebie."); return; }
        float bearing = own.bearingTo(target);
        float diff = (bearing - heading + 540) % 360 - 180;
        finderArrow.setText(Math.abs(diff) < 20 ? "↑" : diff < 0 ? "↖" : "↗");
        finderInfo.setText(summary + "\nObróć telefon zgodnie ze strzałką.");
    }
    private void openMap() {
        if (!fresh(target)) { toast(target == null ? "Najpierw dodaj pozycję znajomego." : "Pozycja jest nieaktualna. Poproś o nową."); return; }
        // Explicitly disclose that the coordinates leave RejTag only after confirmation.
        new AlertDialog.Builder(this).setTitle("Otworzyć mapę?")
            .setMessage("Pozycja znajomego zostanie przekazana wybranej aplikacji mapowej. To przybliżony pomiar z " + age(target) + ".")
            .setNegativeButton("Anuluj", null).setPositiveButton("Otwórz mapę", (d, w) -> {
                if (!fresh(target)) { toast("Pozycja jest nieaktualna. Poproś o nową."); return; }
                String uri = String.format(Locale.US, "geo:%.6f,%.6f?q=%.6f,%.6f", target.getLatitude(), target.getLongitude(), target.getLatitude(), target.getLongitude());
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
                try { startActivity(intent); } catch (android.content.ActivityNotFoundException e) { toast("Brak aplikacji mapowej. Wróć i wybierz «Pokaż współrzędne»."); }
            }).show();
    }
    private void showCoordinates() {
        if (!fresh(target)) { toast(target == null ? "Najpierw dodaj pozycję znajomego." : "Pozycja jest nieaktualna. Poproś o nową."); return; }
        String coordinates = String.format(Locale.US, "%.6f, %.6f", target.getLatitude(), target.getLongitude());
        new AlertDialog.Builder(this).setTitle("Pozycja znajomego")
            .setMessage(coordinates + "\n\nPrzybliżony pomiar z " + age(target) + ". Współrzędne mogą ujawniać prywatne miejsce — udostępniaj je ostrożnie.")
            .setNegativeButton("Zamknij", null).setPositiveButton("Kopiuj", (d, w) -> {
                if (!fresh(target)) { toast("Pozycja jest nieaktualna. Poproś o nową."); return; }
                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                clipboard.setPrimaryClip(ClipData.newPlainText("Pozycja RejTag", coordinates));
                toast("Współrzędne skopiowane.");
            }).show();
    }
    private void openAppSettings() {
        try { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))); }
        catch (android.content.ActivityNotFoundException e) { toast("Otwórz ustawienia aplikacji RejTag w telefonie."); }
    }
    private void openUwbLab() {
        if (android.os.Build.VERSION.SDK_INT < 34) {
            new AlertDialog.Builder(this).setTitle("Laboratorium UWB").setMessage("Ten eksperyment wymaga Androida 14 lub nowszego. Na tym urządzeniu nadal możesz używać wizjera GPS i kompasu.").setPositiveButton("OK", null).show();
            return;
        }
        startActivity(new Intent().setClassName(getPackageName(), getPackageName() + ".UwbLabActivity"));
    }
    private void showCapabilities() {
        PackageManager pm = getPackageManager();
        boolean cameraAvailable = pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY);
        boolean uwb = pm.hasSystemFeature("android.hardware.uwb");
        boolean compass = rotationSensor != null || (accelerometer != null && magnetometer != null);
        String message = "Kamera: " + (cameraAvailable ? "wykryta" : "brak — wizjer działa bez obrazu")
            + "\nKompas: " + (compass ? "czujniki wykryte; jakość zależy od kalibracji" : "brak — dostępna mapa i odległość")
            + "\nLokalizacja: " + (hasLocation() ? (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ? "dostęp dokładny" : "tylko przybliżony") : "brak uprawnienia")
            + "\nUWB: " + (uwb ? "telefon deklaruje sprzęt" : "nie wykryto sprzętu") + " — dostępny eksperymentalny test dwóch telefonów w Laboratorium UWB"
            + "\nBluetooth LE: " + (pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) ? "wykryty" : "brak") + " — pomiar niezaimplementowany"
            + "\nWi-Fi: " + (pm.hasSystemFeature(PackageManager.FEATURE_WIFI) ? "wykryte" : "brak") + " — pomiar niezaimplementowany"
            + "\n\nW tej wersji lokalizacja znajomego to ręcznie przesłana migawka, nie połączenie na żywo.";
        new AlertDialog.Builder(this).setTitle("Możliwości tego telefonu").setMessage(message).setNegativeButton("Zamknij", null).setPositiveButton("Uprawnienia", (d, w) -> openAppSettings()).show();
    }
    @SuppressWarnings("deprecation") private void showFinder() {
        finder = true; locationLabel = null; targetLabel = null;
        cameraStatus = "Przygotowuję kamerę…";
        getWindow().setStatusBarColor(INK); getWindow().getDecorView().setSystemUiVisibility(0);
        FrameLayout root = new FrameLayout(this); root.setBackgroundColor(INK); setContentView(root);
        texture = new TextureView(this); root.addView(texture, new FrameLayout.LayoutParams(-1, -1));
        texture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            public void onSurfaceTextureAvailable(SurfaceTexture s, int w, int h) { openCamera(s); }
            public void onSurfaceTextureSizeChanged(SurfaceTexture s, int w, int h) { }
            public boolean onSurfaceTextureDestroyed(SurfaceTexture s) { releaseCamera(); return true; }
            public void onSurfaceTextureUpdated(SurfaceTexture s) { }
        });
        LinearLayout overlay = column(); overlay.setPadding(dp(24), dp(24), dp(24), dp(28)); root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        TextView back = text("←   WRÓĆ", 13, Color.WHITE, true); back.setShadowLayer(5, 0, 1, Color.BLACK); back.setOnClickListener(v -> showHome()); add(overlay, back, 0);
        TextView title = text("Wizjer", 30, Color.WHITE, true); title.setShadowLayer(7, 0, 1, Color.BLACK); add(overlay, title, 25);
        TextView sub = text("KIERUNEK  /  NIE POZYCJA 3D", 11, Color.WHITE, true); sub.setLetterSpacing(.12f); sub.setShadowLayer(5, 0, 1, Color.BLACK); add(overlay, sub, 7);
        cameraLabel = text(cameraStatus, 12, Color.WHITE, true); cameraLabel.setShadowLayer(5, 0, 1, Color.BLACK); add(overlay, cameraLabel, 10);
        finderArrow = text("—", 90, Color.WHITE, false); finderArrow.setGravity(Gravity.CENTER); finderArrow.setShadowLayer(10, 0, 2, Color.BLACK);
        LinearLayout.LayoutParams center = new LinearLayout.LayoutParams(-1, 0, 1); overlay.addView(finderArrow, center);
        LinearLayout panel = column(); panel.setPadding(dp(20), dp(20), dp(20), dp(20)); panel.setBackground(bg(INK, 22)); add(overlay, panel, 0);
        add(panel, tagWhite("SYGNAŁ ORIENTACYJNY"), 0);
        finderInfo = text("Łączenie z czujnikami…", 16, Color.WHITE, true); finderInfo.setLineSpacing(dp(5), 1); add(panel, finderInfo, 12);
        TextView disclaimer = text("Strzałka wykorzystuje GPS i kompas. Nie wskazuje dokładnego miejsca telefonu za ścianą; sygnał może być błędny, szczególnie w budynkach.", 12, Color.rgb(192, 204, 194), false); disclaimer.setLineSpacing(dp(3), 1); add(panel, disclaimer, 12);
        add(panel, button("Otwórz mapę  ↗", false, this::openMap), 14);
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) cameraStatus = "Bez kamery · działa tryb orientacyjny";
        else if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraStatus = "Bez kamery · działa tryb orientacyjny";
            requestPermissions(new String[]{Manifest.permission.CAMERA}, 11);
        }
        updateLabels();
    }
    private TextView tagWhite(String s) { TextView t = text(s, 11, GREEN, true); t.setLetterSpacing(.12f); return t; }
    @SuppressWarnings("deprecation") private void openCamera(SurfaceTexture surface) {
        if (!finder || camera != null || checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        try {
            int cameraId = -1; Camera.CameraInfo info = new Camera.CameraInfo();
            for (int i = 0; i < Camera.getNumberOfCameras(); i++) { Camera.getCameraInfo(i, info); if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) { cameraId = i; break; } }
            if (cameraId < 0) { cameraStatus = "Brak tylnej kamery · tryb orientacyjny"; updateLabels(); return; }
            camera = Camera.open(cameraId); camera.setDisplayOrientation(info.orientation); camera.setPreviewTexture(surface); camera.startPreview(); cameraStatus = "Kamera aktywna · GPS + kompas"; updateLabels();
        } catch (RuntimeException | IOException e) { releaseCamera(); cameraStatus = "Kamera niedostępna · tryb orientacyjny"; updateLabels(); }
    }
    @SuppressWarnings("deprecation") private void releaseCamera() { if (camera != null) { try { camera.stopPreview(); } catch (RuntimeException ignored) { } camera.release(); camera = null; } }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
    @Override public void onBackPressed() { if (finder) showHome(); else super.onBackPressed(); }
}
