package dev.codex.bedrockpocket;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Runs the verified official Playit agent inside the app's persistent ARM64 Linux rootfs. */
public final class PlayitService extends Service {
    static final String ACTION_START = "dev.codex.bedrockpocket.PLAYIT_START";
    static final String ACTION_STOP = "dev.codex.bedrockpocket.PLAYIT_STOP";
    static final String ACTION_STATE = ServerService.ACTION_STATE;
    private static final int NOTIFICATION_ID = 2402;
    private static volatile String state = "STOPPED";
    private static volatile String claimUrl;
    private static volatile String tunnelAddress;
    private static volatile long bytesIn;
    private static volatile long bytesOut;
    private static volatile int activeTcp;
    private static volatile int activeUdp;
    private static volatile boolean metricsAvailable;
    private volatile Process process;
    private volatile Process frontendProcess;
    private volatile boolean stopRequested;
    private volatile boolean metricsStopRequested;
    private volatile LocalSocket metricsSocket;
    private PowerManager.WakeLock wakeLock;

    static String currentState() { return state; }
    static String currentClaimUrl() { return claimUrl; }
    static String currentTunnelAddress() { return tunnelAddress; }
    static long currentBytesIn() { return bytesIn; }
    static long currentBytesOut() { return bytesOut; }
    static int currentActiveConnections() { return activeTcp + activeUdp; }
    static boolean hasMetrics() { return metricsAvailable; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) stopAgent();
        else if (ACTION_START.equals(action)) startAgent(startId);
        return START_NOT_STICKY;
    }

    private synchronized void startAgent(int startId) {
        if (process != null && process.isAlive()) { publish("RUNNING", "Playit Agent ทำงานอยู่แล้ว"); return; }
        File base = new File(getFilesDir(), "bds");
        if (!ServerInstaller.isInstalled(base)) {
            publish("ERROR", "ติดตั้ง BDS และ Ubuntu runtime ก่อน เพื่อให้แอปรัน Playit Agent ใน Linux ได้");
            stopSelf(startId);
            return;
        }
        startForeground(NOTIFICATION_ID, notification("กำลังเตรียม Playit Agent"));
        stopRequested = false;
        metricsStopRequested = false;
        tunnelAddress = null;
        bytesIn = 0;
        bytesOut = 0;
        activeTcp = 0;
        activeUdp = 0;
        metricsAvailable = false;
        state = "STARTING";
        claimUrl = null;
        publish(state, "เริ่มเตรียม Playit Agent");
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, getPackageName() + ":Playit");
            wakeLock.acquire();
        } catch (Exception ignored) {}
        new Thread(() -> runAgent(base, startId), "Playit-Agent").start();
    }

    private void runAgent(File base, int startId) {
        Process launched = null;
        try {
            File ubuntu = new File(base, "ubuntu");
            File proot = new File(base, "proot/root/bin/proot");
            File playitDirectory = new File(base, "playit");
            File prootTmp = new File(getCacheDir(), "proot-tmp");
            if (!prootTmp.mkdirs() && !prootTmp.isDirectory()) throw new java.io.IOException("สร้างพื้นที่ชั่วคราวของ PRoot ไม่ได้");
            File agent = PlayitManager.install(getFilesDir(), line -> log("[Playit] " + line));
            if (stopRequested) {
                publish("STOPPED", "ยกเลิกการเริ่ม Playit Agent แล้ว");
                return;
            }
            ArrayList<String> args = new ArrayList<>();
            Collections.addAll(args, proot.getAbsolutePath(), "-0", "--link2symlink", "-r", ubuntu.getAbsolutePath(),
                    "-b", "/dev", "-b", "/proc", "-b", "/sys", "-b", playitDirectory.getAbsolutePath() + ":/opt/playit",
                    "-w", "/root", "/opt/playit/" + agent.getName());
            ProcessBuilder pb = new ProcessBuilder(args);
            pb.redirectErrorStream(true);
            File l2s = new File(ubuntu, ".l2s");
            if (!l2s.mkdirs() && !l2s.isDirectory()) throw new java.io.IOException("สร้างพื้นที่ hard-link compatibility ไม่ได้");
            pb.environment().put("PROOT_TMP_DIR", prootTmp.getAbsolutePath());
            pb.environment().put("PROOT_L2S_DIR", l2s.getAbsolutePath());
            pb.environment().put("HOME", "/root");
            pb.environment().put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
            pb.environment().put("TMPDIR", "/tmp");
            launched = pb.start();
            process = launched;
            state = "RUNNING";
            publish(state, "รัน Playit Agent v" + PlayitManager.VERSION + " จริงบน Linux ARM64");
            startMetricsMonitor(ubuntu);
            startClaimFrontend(base, ubuntu, proot, prootTmp, playitDirectory, l2s);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(launched.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log("[Playit] " + line);
                    Matcher matcher = Pattern.compile("https://playit\\.gg/claim/[A-Za-z0-9_-]+").matcher(line);
                    if (matcher.find()) {
                        if (!matcher.group().equals(claimUrl)) {
                            claimUrl = matcher.group();
                            state = "WAITING_FOR_CLAIM";
                            publish(state, "Playit Agent รอให้ยืนยันบัญชี · เปิดลิงก์ claim ในหน้านี้");
                        }
                    } else if (line.toLowerCase(java.util.Locale.ROOT).contains("connected")
                            || line.toLowerCase(java.util.Locale.ROOT).contains("registered")) {
                        state = "RUNNING";
                        publish(state, "Playit Agent รายงานการเชื่อมต่อแล้ว");
                    }
                }
            }
            int exit = launched.waitFor();
            process = null;
            if ("STOPPING".equals(state) || exit == 0) publish("STOPPED", "Playit Agent หยุดแล้ว");
            else publish("ERROR", "Playit Agent จบการทำงาน (exit " + exit + ") · เปิด Console ดู log");
        } catch (Exception e) {
            process = null;
            if (stopRequested || "STOPPING".equals(state)) publish("STOPPED", "Playit Agent หยุดแล้ว");
            else publish("ERROR", "เริ่ม Playit Agent ไม่สำเร็จ: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        } finally {
            if (launched != null && launched.isAlive()) launched.destroy();
            Process frontend = frontendProcess;
            if (frontend != null && frontend.isAlive()) frontend.destroy();
            releaseWakeLock();
            stopForeground(true);
            stopSelf(startId);
        }
    }

    /** Reads the official Playit daemon's local JSON IPC stream. This connection is read-only. */
    private void startMetricsMonitor(File ubuntu) {
        metricsStopRequested = false;
        new Thread(() -> {
            File socketFile = new File(ubuntu, "run/playit/playitd.sock");
            boolean failureLogged = false;
            while (!metricsStopRequested && process != null && process.isAlive()) {
                LocalSocket socket = new LocalSocket();
                try {
                    socket.connect(new LocalSocketAddress(socketFile.getAbsolutePath(), LocalSocketAddress.Namespace.FILESYSTEM));
                    metricsSocket = socket;
                    BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    java.io.BufferedWriter writer = new java.io.BufferedWriter(new java.io.OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
                    String hello = reader.readLine();
                    if (hello == null || !hello.contains("\"hello\"")) throw new java.io.IOException("Playit IPC handshake ไม่ถูกต้อง");
                    writer.write("{\"ipc_version\":2,\"request_id\":1,\"request\":{\"type\":\"subscribe\"}}\n");
                    writer.flush();
                    String line = reader.readLine();
                    if (line == null) throw new java.io.IOException("Playit IPC ปิดการเชื่อมต่อก่อนส่งสถานะเริ่มต้น");
                    updateMetricsFromIpc(line);
                    failureLogged = false;
                    while (!metricsStopRequested && process != null && process.isAlive() && (line = reader.readLine()) != null) updateMetricsFromIpc(line);
                } catch (Exception e) {
                    metricsAvailable = false;
                    if (!failureLogged && !metricsStopRequested && process != null && process.isAlive()) {
                        log("[Playit metrics] อ่าน IPC ไม่สำเร็จ: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
                        failureLogged = true;
                    }
                } finally {
                    metricsSocket = null;
                    try { socket.close(); } catch (Exception ignored) {}
                }
                if (!metricsStopRequested && process != null && process.isAlive()) {
                    try { Thread.sleep(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                }
            }
        }, "Playit-Metrics-IPC").start();
    }

    private void updateMetricsFromIpc(String json) {
        try {
            org.json.JSONObject envelope = new org.json.JSONObject(json);
            String kind = envelope.optString("message_kind", "");
            org.json.JSONObject data = envelope.optJSONObject("data");
            if (data == null) return;
            if ("response".equals(kind)) {
                org.json.JSONObject response = data.optJSONObject("response");
                org.json.JSONObject subscription = response == null ? null : response.optJSONObject("data");
                org.json.JSONObject snapshot = subscription == null ? null : subscription.optJSONObject("snapshot");
                if (snapshot != null) {
                    org.json.JSONObject stats = snapshot.optJSONObject("stats");
                    if (stats != null) updateStats(stats);
                    updateLifecycle(snapshot.optJSONObject("lifecycle"));
                }
            } else if ("event".equals(kind)) {
                org.json.JSONObject event = data.optJSONObject("event");
                if (event == null) return;
                String type = event.optString("type", "");
                org.json.JSONObject eventData = event.optJSONObject("data");
                if ("stats".equals(type) && eventData != null) updateStats(eventData);
                else if ("lifecycle".equals(type)) updateLifecycle(eventData);
            }
        } catch (Exception e) {
            log("[Playit metrics] ข้อมูล IPC ไม่ถูกต้อง: " + e.getClass().getSimpleName());
        }
    }

    private void updateStats(org.json.JSONObject stats) {
        bytesIn = Math.max(0, stats.optLong("bytes_in", bytesIn));
        bytesOut = Math.max(0, stats.optLong("bytes_out", bytesOut));
        activeTcp = Math.max(0, stats.optInt("active_tcp", activeTcp));
        activeUdp = Math.max(0, stats.optInt("active_udp", activeUdp));
        metricsAvailable = true;
    }

    private void updateLifecycle(org.json.JSONObject lifecycle) {
        if (lifecycle == null) return;
        org.json.JSONObject running = lifecycle.optJSONObject("data");
        if (running == null || !"running".equals(lifecycle.optString("state"))) {
            tunnelAddress = null;
            return;
        }
        org.json.JSONArray tunnels = running.optJSONArray("tunnels");
        StringBuilder addresses = new StringBuilder();
        if (tunnels != null) for (int i = 0; i < tunnels.length(); i++) {
            org.json.JSONObject tunnel = tunnels.optJSONObject(i);
            if (tunnel == null || tunnel.optBoolean("is_disabled", false)) continue;
            String address = tunnel.optString("display_address", "").trim();
            if (!address.isEmpty() && !"null".equals(address)) {
                if (addresses.length() > 0) addresses.append('\n');
                addresses.append(address);
            }
        }
        tunnelAddress = addresses.length() == 0 ? null : addresses.toString();
    }

    private void startClaimFrontend(File base, File ubuntu, File proot, File prootTmp, File playitDirectory, File l2s) {
        new Thread(() -> {
            try {
                File cli = PlayitManager.installCli(getFilesDir(), line -> log("[Playit setup] " + line));
                if (stopRequested || process == null || !process.isAlive()) return;
                if (isSecretConfigured(cli, ubuntu, proot, prootTmp, playitDirectory, l2s)) {
                    claimUrl = null;
                    publish("RUNNING", "Agent ผูกบัญชี Playit แล้ว · พร้อมใช้");
                    return;
                }
                ArrayList<String> args = new ArrayList<>();
                Collections.addAll(args, proot.getAbsolutePath(), "-0", "--link2symlink", "-r", ubuntu.getAbsolutePath(),
                        "-b", "/dev", "-b", "/proc", "-b", "/sys", "-b", playitDirectory.getAbsolutePath() + ":/opt/playit",
                        "-w", "/root", "/opt/playit/" + cli.getName(), "--stdout", "--socket-path", "/run/playit/playitd.sock", "setup");
                ProcessBuilder pb = new ProcessBuilder(args);
                pb.redirectErrorStream(true);
                pb.environment().put("PROOT_TMP_DIR", prootTmp.getAbsolutePath());
                pb.environment().put("PROOT_L2S_DIR", l2s.getAbsolutePath());
                pb.environment().put("HOME", "/root");
                pb.environment().put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
                pb.environment().put("TMPDIR", "/tmp");
                Process setup = pb.start();
                frontendProcess = setup;
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(setup.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        log("[Playit setup] " + line);
                        Matcher matcher = Pattern.compile("https://playit\\.gg/claim/[A-Za-z0-9_-]+").matcher(line);
                        if (matcher.find()) {
                            claimUrl = matcher.group();
                            publish("WAITING_FOR_CLAIM", "Playit Agent รอให้ยืนยันบัญชี · เปิดลิงก์ claim ในหน้านี้");
                        } else if (line.toLowerCase(java.util.Locale.ROOT).contains("setup is complete")) {
                            claimUrl = null;
                            publish("RUNNING", "ผูกบัญชี Playit สำเร็จ · Agent พร้อมใช้");
                        }
                    }
                }
                int exit = setup.waitFor();
                if (exit != 0 && !stopRequested) log("[Playit setup] ขั้น claim จบด้วย exit " + exit);
                frontendProcess = null;
            } catch (Exception e) {
                if (!stopRequested) log("[Playit setup] เริ่มขั้น claim ไม่สำเร็จ: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            }
        }, "Playit-Claim-Setup").start();
    }

    private boolean isSecretConfigured(File cli, File ubuntu, File proot, File prootTmp, File playitDirectory, File l2s) throws Exception {
        for (int attempt = 0; attempt < 12; attempt++) {
            ArrayList<String> args = new ArrayList<>();
            Collections.addAll(args, proot.getAbsolutePath(), "-0", "--link2symlink", "-r", ubuntu.getAbsolutePath(),
                    "-b", "/dev", "-b", "/proc", "-b", "/sys", "-b", playitDirectory.getAbsolutePath() + ":/opt/playit",
                    "-w", "/root", "/opt/playit/" + cli.getName(), "--stdout", "--socket-path", "/run/playit/playitd.sock", "status");
            ProcessBuilder pb = new ProcessBuilder(args);
            pb.redirectErrorStream(true);
            pb.environment().put("PROOT_TMP_DIR", prootTmp.getAbsolutePath());
            pb.environment().put("PROOT_L2S_DIR", l2s.getAbsolutePath());
            pb.environment().put("HOME", "/root");
            pb.environment().put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
            pb.environment().put("TMPDIR", "/tmp");
            Process status = pb.start();
            String configured = null;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(status.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    Matcher matcher = Pattern.compile("(?i)Secret configured:\\s*(true|false)").matcher(line);
                    if (matcher.find()) configured = matcher.group(1);
                }
            }
            if (!status.waitFor(4, TimeUnit.SECONDS)) status.destroyForcibly();
            if (configured != null) {
                log("[Playit status] Secret configured: " + configured);
                return Boolean.parseBoolean(configured);
            }
            Thread.sleep(250);
        }
        throw new java.io.IOException("อ่านสถานะ Agent ผ่าน Playit CLI ไม่ได้");
    }

    private synchronized void stopAgent() {
        Process p = process;
        if (p == null && "STARTING".equals(state)) {
            stopRequested = true;
            publish("STOPPING", "กำลังยกเลิกการเริ่ม Playit Agent");
            return;
        }
        if (p == null || !p.isAlive()) {
            publish("STOPPED", "Playit Agent หยุดอยู่แล้ว");
            stopForeground(true);
            stopSelf();
            return;
        }
        state = "STOPPING";
        publish(state, "กำลังหยุด Playit Agent");
        new Thread(() -> {
            requestDaemonStop();
            Process frontend = frontendProcess;
            if (frontend != null && frontend.isAlive()) frontend.destroy();
            try {
                if (!p.waitFor(8, TimeUnit.SECONDS)) {
                    p.destroy();
                    if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroyForcibly();
                }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); p.destroyForcibly(); }
        }, "Playit-Stop").start();
    }

    private void requestDaemonStop() {
        File base = new File(getFilesDir(), "bds");
        File ubuntu = new File(base, "ubuntu");
        File proot = new File(base, "proot/root/bin/proot");
        File playitDirectory = new File(base, "playit");
        File prootTmp = new File(getCacheDir(), "proot-tmp");
        File l2s = new File(ubuntu, ".l2s");
        if (!PlayitManager.isCliInstalled(getFilesDir())) return;
        try {
            ArrayList<String> args = new ArrayList<>();
            Collections.addAll(args, proot.getAbsolutePath(), "-0", "--link2symlink", "-r", ubuntu.getAbsolutePath(),
                    "-b", "/dev", "-b", "/proc", "-b", "/sys", "-b", playitDirectory.getAbsolutePath() + ":/opt/playit",
                    "-w", "/root", "/opt/playit/playit-cli-linux-aarch64", "--stdout", "--socket-path", "/run/playit/playitd.sock", "stop");
            ProcessBuilder pb = new ProcessBuilder(args);
            pb.redirectErrorStream(true);
            pb.redirectOutput(new File(getCacheDir(), "playit-stop.log"));
            pb.environment().put("PROOT_TMP_DIR", prootTmp.getAbsolutePath());
            pb.environment().put("PROOT_L2S_DIR", l2s.getAbsolutePath());
            pb.environment().put("HOME", "/root");
            pb.environment().put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
            pb.environment().put("TMPDIR", "/tmp");
            Process stop = pb.start();
            if (!stop.waitFor(8, TimeUnit.SECONDS)) stop.destroyForcibly();
            File output = new File(getCacheDir(), "playit-stop.log");
            if (output.isFile()) {
                for (String line : java.nio.file.Files.readAllLines(output.toPath(), StandardCharsets.UTF_8)) log("[Playit stop] " + line);
                output.delete();
            }
        } catch (Exception e) {
            log("[Playit stop] CLI shutdown did not complete: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    private void log(String line) {
        ServerInstaller.appendLog(new File(getFilesDir(), "app.log"), line);
        Intent update = new Intent(ACTION_STATE).setPackage(getPackageName());
        update.putExtra(ServerService.EXTRA_MESSAGE, "LOG:" + line);
        sendBroadcast(update);
    }

    private void publish(String next, String message) {
        state = next;
        if ("STOPPED".equals(next)) {
            claimUrl = null;
            tunnelAddress = null;
            bytesIn = 0;
            bytesOut = 0;
            activeTcp = 0;
            activeUdp = 0;
            metricsAvailable = false;
        }
        ServerInstaller.appendLog(new File(getFilesDir(), "app.log"), "[Playit] " + message);
        Intent update = new Intent(ACTION_STATE).setPackage(getPackageName());
        update.putExtra("playit_state", next);
        update.putExtra(ServerService.EXTRA_MESSAGE, "PLAYIT_STATE:" + message);
        sendBroadcast(update);
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.notify(NOTIFICATION_ID, notification("Playit: " + message));
    }

    private Notification notification(String text) {
        String channel = "playit_agent";
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(new NotificationChannel(channel, "Playit Agent", NotificationManager.IMPORTANCE_LOW));
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 2402, open, Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, channel) : new Notification.Builder(this);
        return builder.setContentTitle("BedrockPocket").setContentText(text).setSmallIcon(android.R.drawable.stat_sys_upload_done).setContentIntent(pending).setOngoing(true).build();
    }

    private void releaseWakeLock() {
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) {}
        wakeLock = null;
    }

    @Override public void onDestroy() {
        metricsStopRequested = true;
        LocalSocket metrics = metricsSocket;
        if (metrics != null) try { metrics.close(); } catch (Exception ignored) {}
        Process p = process;
        if (p != null && p.isAlive()) p.destroy();
        Process frontend = frontendProcess;
        if (frontend != null && frontend.isAlive()) frontend.destroy();
        releaseWakeLock();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
