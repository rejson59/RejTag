package pl.rejtag.app;

import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Foreground-only RejHub session client. Password and session are never persisted. */
final class LiveClient {
    interface Callback { void done(Object response, String error); }
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile String token;
    private volatile String username;
    private volatile int generation;
    private volatile boolean foreground;

    boolean configured() {
        return BuildConfig.SUPABASE_URL.startsWith("https://") && !BuildConfig.SUPABASE_ANON_KEY.isEmpty();
    }
    boolean signedIn() { return token != null; }
    void setForeground(boolean value) { foreground = value; }
    String username() { return username; }
    void clear() { token = null; username = null; generation++; }
    void close() { clear(); executor.shutdownNow(); }

    void signIn(String name, String password, Callback callback) {
        JSONObject data = new JSONObject();
        try { data.put("p_username", name); data.put("p_pin", password); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
        request("/rest/v1/rpc/community_login", data, false, (response, error) -> {
            if (error != null) { callback.done(null, error); return; }
            if (!(response instanceof JSONObject)) { callback.done(null, "Nieprawidłowa odpowiedź serwera."); return; }
            JSONObject json = (JSONObject) response;
            String session = json.optString("token", "");
            String actualName = json.optString("username", "");
            if (session.length() < 16 || actualName.isEmpty()) { callback.done(null, "Nie udało się zalogować do RejHub."); return; }
            token = session;
            username = actualName;
            callback.done(response, null);
        });
    }
    void rpc(String name, JSONObject args, Callback callback) {
        if (!name.startsWith("rejtag_")) { callback.done(null, "Nieprawidłowa operacja."); return; }
        String session = token;
        if (session == null) { callback.done(null, "Zaloguj się ponownie."); return; }
        try {
            JSONObject body = new JSONObject(args.toString());
            body.put("p_session", session);
            request("/rest/v1/rpc/rejtag_v2_" + name.substring("rejtag_".length()), body, true, callback);
        } catch (Exception e) { callback.done(null, "Nieprawidłowe dane żądania."); }
    }
    private void request(String path, JSONObject body, boolean authenticated, Callback callback) {
        if (!configured()) { callback.done(null, "Tryb na żywo wymaga skonfigurowania projektu Supabase w pliku local.properties."); return; }
        final String session = token;
        final int currentGeneration = generation;
        if (authenticated && session == null) { callback.done(null, "Zaloguj się ponownie."); return; }
        executor.execute(() -> {
            Object result = null;
            String error = null;
            HttpURLConnection conn = null;
            try {
                if (!foreground || currentGeneration != generation) throw new IllegalStateException("Inactive");
                String base = BuildConfig.SUPABASE_URL.replaceAll("/+$", "");
                conn = (HttpURLConnection) new URL(base + path).openConnection();
                conn.setInstanceFollowRedirects(false); // Never forward authorization to another host.
                conn.setConnectTimeout(10000); conn.setReadTimeout(10000);
                conn.setRequestMethod("POST"); conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("apikey", BuildConfig.SUPABASE_ANON_KEY);
                conn.setRequestProperty("Authorization", "Bearer " + BuildConfig.SUPABASE_ANON_KEY);
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream out = conn.getOutputStream()) { out.write(bytes); }
                int status = conn.getResponseCode();
                InputStream stream = status >= 200 && status < 300 ? conn.getInputStream() : conn.getErrorStream();
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                if (stream != null) try (InputStream in = stream) {
                    byte[] chunk = new byte[4096]; int read;
                    while ((read = in.read(chunk)) != -1) { buffer.write(chunk, 0, read); if (buffer.size() > 100_000) throw new IllegalStateException("Response too large"); }
                }
                String raw = new String(buffer.toByteArray(), StandardCharsets.UTF_8);
                if (status < 200 || status >= 300) {
                    if (!authenticated && status == 401) error = "Nieprawidłowa nazwa lub hasło / PIN.";
                    else if (status == 429) error = "Zbyt wiele prób. Spróbuj ponownie później.";
                    else if (authenticated && (status == 400 || status == 401 || status == 403)) error = "Sesja wygasła lub operacja jest niedozwolona. Sprawdź konto i zaloguj się ponownie.";
                    else error = "Nie udało się wykonać operacji (HTTP " + status + ").";
                } else if (!raw.trim().isEmpty()) result = new JSONTokener(raw).nextValue();
            } catch (Exception e) { error = "Brak połączenia z usługą. Sprawdź internet i konfigurację."; }
            finally { if (conn != null) conn.disconnect(); }
            Object answer = result; String failure = error;
            main.post(() -> { if (currentGeneration == generation) callback.done(answer, failure); });
        });
    }
}
