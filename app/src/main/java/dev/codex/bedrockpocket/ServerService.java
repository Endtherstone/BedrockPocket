package dev.codex.bedrockpocket;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ServerService extends Service {
    static final String ACTION_INSTALL = "dev.codex.bedrockpocket.INSTALL";
    static final String ACTION_START = "dev.codex.bedrockpocket.START";
    static final String ACTION_STOP = "dev.codex.bedrockpocket.STOP";
    static final String ACTION_QUERY = "dev.codex.bedrockpocket.QUERY";
    static final String ACTION_COMMAND = "dev.codex.bedrockpocket.COMMAND";
    static final String ACTION_STATE = "dev.codex.bedrockpocket.STATE";
    static final String EXTRA_STATE = "state";
    static final String EXTRA_MESSAGE = "message";
    private static final int NOTIFICATION_ID = 2401;
    private volatile Process process;
    private volatile OutputStream stdin;
    private volatile String state = "STOPPED";
    private volatile boolean readySeen;
    private static volatile int metricsRootPid = -1;
    private PowerManager.WakeLock wakeLock;

    static int metricsRootPid() { return metricsRootPid; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_QUERY : intent.getAction();
        if (ACTION_INSTALL.equals(action)) {
            installBds(startId);
        } else if (ACTION_STOP.equals(action)) {
            stopServer();
        } else if (ACTION_COMMAND.equals(action)) {
            sendCommand(intent == null ? null : intent.getStringExtra(EXTRA_MESSAGE));
        } else if (ACTION_QUERY.equals(action)) {
            if (process != null && process.isAlive()) publish(state, "ตรวจสอบสถานะแล้ว");
            else if (ServerInstaller.isInstalled(new File(getFilesDir(), "bds"))) publish("INSTALLED", "ติดตั้ง " + ServerInstaller.SERVER_VERSION + " แล้ว");
            else if (ServerInstaller.hasStagedBds(new File(getFilesDir(), "bds-installing"))) publish("STOPPED", "ดาวน์โหลด Mojang BDS แล้ว · Linux runtime ยังไม่พร้อม กดติดตั้งเพื่อทำต่อ");
            else { publish("STOPPED", "ยังไม่ได้ติดตั้ง BDS"); stopSelf(startId); }
        } else if (ACTION_START.equals(action)) {
            startForeground(NOTIFICATION_ID, notification("กำลังเตรียมเริ่ม Mojang BDS"));
            if (process != null && process.isAlive()) {
                publish(state, "BDS กำลังทำงานอยู่แล้ว");
            } else if (!ServerInstaller.isInstalled(new File(getFilesDir(), "bds"))) {
                publish("ERROR", "ติดตั้ง BDS และ Box64 ก่อน"); stopForeground(true); stopSelf(startId);
            } else {
                SharedPreferences preferences = getSharedPreferences("server", MODE_PRIVATE);
                if (!preferences.getBoolean("eula_accepted", false)) {
                    publish("ERROR", "กรุณาอ่านและยอมรับ Minecraft EULA ก่อนเริ่ม BDS"); stopForeground(true); stopSelf(startId);
                } else {
                    File activeServer=ServerProfileManager.activeServerDir(this);
                    if(!activeServer.isDirectory()){publish("ERROR","ไม่พบโฟลเดอร์ของโปรไฟล์ "+ServerProfileManager.name(this,ServerProfileManager.activeId(this)));stopForeground(true);stopSelf(startId);return START_NOT_STICKY;}
                    try { AddonManager.activateInstalled(activeServer); }
                    catch(Exception e){publish("ERROR","เปิดใช้แอดออนกับโลกไม่สำเร็จ: "+e.getMessage());stopForeground(true);stopSelf(startId);return START_NOT_STICKY;}
                    startForeground(NOTIFICATION_ID, notification("กำลังเริ่ม Mojang BDS")); launchServer();
                }
            }
        }
        return START_NOT_STICKY;
    }

    private synchronized void installBds(int startId) {
        if ("INSTALLING".equals(state)) { publish(state, "กำลังติดตั้ง BDS อยู่แล้ว"); return; }
        File base = new File(getFilesDir(), "bds");
        if (ServerInstaller.isInstalled(base)) { publish("INSTALLED", "ติดตั้ง BDS แล้ว"); stopSelf(startId); return; }
        startForeground(NOTIFICATION_ID, notification("กำลังติดตั้ง BDS และ Ubuntu"));
        publish("INSTALLING", "เตรียม Linux runtime");
        new Thread(() -> {
            try {
                ServerInstaller.install(base, getCacheDir(), this, message -> {
                    ServerInstaller.appendLog(new File(getFilesDir(), "app.log"), message);
                    publish("INSTALLING", message);
                });
                publish("INSTALLED", "ติดตั้ง " + ServerInstaller.SERVER_VERSION + " พร้อม Box64 แล้ว");
            } catch (Exception e) {
                String message = ServerInstaller.userMessage(e);
                ServerInstaller.appendLog(new File(getFilesDir(), "app.log"), "ติดตั้ง BDS ไม่สำเร็จ: " + message);
                publish("ERROR", message);
            } finally {
                stopForeground(true);
                stopSelf();
            }
        }, "BDS-Install").start();
    }

    private void launchServer() {
        getSharedPreferences("server", MODE_PRIVATE).edit().putBoolean("user_stop_requested", false).apply();
        File base = new File(getFilesDir(), "bds");
        File ubuntu = new File(base, "ubuntu");
        File proot = new File(base, "proot/root/bin/proot");
        File prootTmp = new File(getCacheDir(), "proot-tmp");
        if (!prootTmp.mkdirs() && !prootTmp.isDirectory()) {
            publish("ERROR", "สร้างพื้นที่ชั่วคราวของ PRoot ไม่ได้"); stopForeground(true); stopSelf(); return;
        }
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, getPackageName() + ":BDS");
            wakeLock.acquire();
        } catch (Exception ignored) {}
        readySeen = false;
        AtomicBoolean startupTimedOut = new AtomicBoolean(false);
        publish("STARTING", "เปิด Mojang BDS ผ่าน Ubuntu ARM64 + Box64");
        new Thread(() -> {
            boolean minecraftServicesFailed = false;
            try {
                ArrayList<String> command = new ArrayList<>();
                String profileId=ServerProfileManager.activeId(this);File profileDir=ServerProfileManager.serverDir(this,profileId);String guestWork=ServerProfileManager.guestWorkingDirectory(profileId);int serverPort=readServerPort(profileDir);
                if(!profileDir.isDirectory())throw new java.io.IOException("ไม่พบโฟลเดอร์ของโปรไฟล์เซิร์ฟเวอร์");
                Collections.addAll(command, proot.getAbsolutePath(), "-0", "--link2symlink", "-r", ubuntu.getAbsolutePath(), "-b", "/dev", "-b", "/proc", "-b", "/sys");
                if(!"default".equals(profileId)){File mount=new File(ubuntu,"mnt/bp_profile");if(!mount.mkdirs()&&!mount.isDirectory())throw new java.io.IOException("เตรียมจุดเชื่อมต่อโปรไฟล์ไม่ได้");Collections.addAll(command,"-b",profileDir.getAbsolutePath()+":/mnt/bp_profile");}
                Collections.addAll(command,"-w",guestWork,"/usr/bin/box64","./bedrock_server");
                File pidFile = new File(getCacheDir(), "proot.pid");
                if (pidFile.exists()) pidFile.delete();
                StringBuilder shellCommand = new StringBuilder("echo $$ > ").append(shellQuote(pidFile.getAbsolutePath())).append("; exec");
                for (String arg : command) shellCommand.append(' ').append(shellQuote(arg));
                ArrayList<String> args = new ArrayList<>();
                Collections.addAll(args, "/system/bin/sh", "-c", shellCommand.toString());
                ProcessBuilder pb = new ProcessBuilder(args);
                pb.directory(profileDir); pb.redirectErrorStream(true);
                File l2s = new File(ubuntu, ".l2s");
                if (!l2s.mkdirs() && !l2s.isDirectory()) throw new java.io.IOException("สร้างพื้นที่ hard-link compatibility ไม่ได้");
                pb.environment().put("PROOT_TMP_DIR", prootTmp.getAbsolutePath());
                pb.environment().put("PROOT_L2S_DIR", new File(ubuntu, ".l2s").getAbsolutePath());
                pb.environment().put("BOX64_LD_LIBRARY_PATH", "/opt/bds:/usr/lib/x86_64-linux-gnu:/lib/x86_64-linux-gnu");
                pb.environment().put("BOX64_PATH", "/usr/bin:/bin");
                pb.environment().put("BOX64_DYNAREC", "1");
                pb.environment().put("HOME", "/root");
                pb.environment().put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
                pb.environment().put("TMPDIR", "/tmp");
                Process launched = pb.start();
                process = launched;
                metricsRootPid = readPidFile(pidFile);
                stdin = launched.getOutputStream();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(launched.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        ServerInstaller.appendLog(new File(getFilesDir(), "app.log"), line);
                        enforceAppBan(line);
                        Intent logEvent = new Intent(ACTION_STATE);
                        logEvent.setPackage(getPackageName());
                        logEvent.putExtra(EXTRA_STATE, state);
                        logEvent.putExtra(EXTRA_MESSAGE, "LOG:" + line);
                        sendBroadcast(logEvent);
                        String lower = line.toLowerCase(java.util.Locale.ROOT);
                        if (lower.contains("waiting for minecraft services")) {
                            publish("STARTING", "รอการเชื่อมต่อ Minecraft services");
                        }
                        if (lower.contains("could not connect to minecraft services")) {
                            minecraftServicesFailed = true;
                            publish("ERROR", "BDS ติดต่อ Minecraft services ไม่ได้ จึงไม่รับผู้เล่น ตรวจเครือข่ายของมือถือ");
                        }
                        if (!readySeen && lower.contains("server started.")) {
                            if (probeLocalNetherNetTcp(serverPort)) {
                                readySeen = true;
                                publish("RUNNING", "Mojang BDS ตอบ NetherNet TCP signaling · พอร์ต "+serverPort);
                            } else {
                                String message = "BDS แจ้งว่าเริ่มแล้ว แต่ TCP signaling ภายในเครื่องที่พอร์ต "+serverPort+" ไม่ตอบ";
                                ServerInstaller.appendLog(new File(getFilesDir(), "app.log"), message);
                                publish("ERROR", message);

                            }
                        }
                    }
                }
                int exit = launched.waitFor();
                process = null; stdin = null;
                metricsRootPid = -1;
                boolean userRequestedStop=getSharedPreferences("server",MODE_PRIVATE).getBoolean("user_stop_requested",false);
                if (userRequestedStop || "STOPPING".equals(state)) {getSharedPreferences("server",MODE_PRIVATE).edit().putBoolean("user_stop_requested",false).apply();publish(ServerInstaller.isInstalled(base) ? "INSTALLED" : "STOPPED", "BDS หยุดแล้ว (exit " + exit + ")");}
                else if (minecraftServicesFailed) publish("ERROR", "BDS ติดต่อ Minecraft services ไม่ได้และหยุดก่อนรับผู้เล่น");
                else if (exit == 0) publish(ServerInstaller.isInstalled(base) ? "INSTALLED" : "STOPPED", "BDS หยุดแล้ว (exit " + exit + ")");
                else if ("ERROR".equals(state)) publish("ERROR", "BDS แจ้งเริ่มแล้วแต่ TCP signaling ในเครื่องไม่ตอบ");
                else publish("ERROR", readySeen ? "BDS หยุดโดยไม่คาดคิด (exit " + exit + ")" : "BDS เริ่มไม่สำเร็จ (exit " + exit + ") ตรวจบันทึกเพื่อดูข้อผิดพลาด");
            } catch (Exception e) {
                process = null; stdin = null;
                metricsRootPid = -1;
                if (startupTimedOut.get()) {
                    publish("ERROR", "BDS ไม่รายงานว่าเริ่มสำเร็จภายใน 5 นาที; ตรวจ log ล่าสุดและการเชื่อมต่อ Minecraft services");
                } else if (minecraftServicesFailed) {
                    publish("ERROR", "BDS ติดต่อ Minecraft services ไม่ได้และหยุดก่อนรับผู้เล่น ตรวจเครือข่ายของมือถือ");
                } else {
                    publish("ERROR", "เปิด BDS ไม่สำเร็จ: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
                }
            } finally {
                releaseWakeLock(); stopForeground(true); stopSelf();
            }
        }, "BDS-Process").start();
        new Thread(() -> {
            try { Thread.sleep(300000); } catch (InterruptedException e) { return; }
            Process p = process;
            if (p != null && p.isAlive() && !readySeen && "STARTING".equals(state)) {
                startupTimedOut.set(true);
                publish("ERROR", "BDS ยังไม่รายงานว่าเริ่มสำเร็จภายใน 5 นาที; ตรวจ log ล่าสุดและการเชื่อมต่อ Minecraft services");
                p.destroy();
                new Thread(() -> { try { if (!p.waitFor(8, TimeUnit.SECONDS)) p.destroyForcibly(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); p.destroyForcibly(); } }, "BDS-Startup-Stop").start();
            }
        }, "BDS-Startup-Timeout").start();
    }

    private int readPidFile(File pidFile) {
        for (int attempt = 0; attempt < 20; attempt++) {
            try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(pidFile))) {
                String value = reader.readLine();
                if (value != null) return Integer.parseInt(value.trim());
            } catch (Exception ignored) {}
            try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        return -1;
    }
    private String shellQuote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }
    private int readServerPort(File profileDir){try{File f=new File(profileDir,"server.properties");for(String line:java.nio.file.Files.readAllLines(f.toPath(),StandardCharsets.UTF_8))if(line.startsWith("server-port=")){int port=Integer.parseInt(line.substring("server-port=".length()).trim());if(port>0&&port<65536)return port;}}catch(Exception ignored){}return 19132;}

    private boolean probeLocalNetherNetTcp(int port) {
        ArrayList<String> hosts = new ArrayList<>();
        try {
            java.util.Enumeration<java.net.NetworkInterface> interfaces = java.net.NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                java.net.NetworkInterface network = interfaces.nextElement();
                if (!network.isUp() || network.isLoopback()) continue;
                java.util.Enumeration<InetAddress> addresses = network.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address instanceof java.net.Inet4Address && !address.isLoopbackAddress() && !address.isLinkLocalAddress()) hosts.add(address.getHostAddress());
                }
            }
        } catch (Exception ignored) {}
        hosts.add("127.0.0.1"); hosts.add("::1");
        for (String host : hosts) {
            try (java.net.Socket socket = new java.net.Socket()) {
                socket.connect(new java.net.InetSocketAddress(InetAddress.getByName(host), port), 1000);
                return true;
            } catch (Exception ignored) {}
        }
        return false;
    }

    private void sendCommand(String command) {
        String cmd = command == null ? "" : command.trim();
        OutputStream out = stdin;
        if (cmd.isEmpty()) return;
        if (cmd.length() > 300 || cmd.indexOf('\n') >= 0 || cmd.indexOf('\r') >= 0 || cmd.indexOf('\0') >= 0) {
            publish(state, "คำสั่งยาวเกินไปหรือมีอักขระควบคุม"); return;
        }
        if (out == null || process == null || !process.isAlive()) {
            publish("ERROR", "BDS ยังไม่ได้ทำงาน จึงส่งคำสั่งไม่ได้"); return;
        }
        try {
            out.write((cmd + "\n").getBytes(StandardCharsets.UTF_8)); out.flush();
            ServerInstaller.appendLog(new File(getFilesDir(), "app.log"), "> " + cmd);
            publish(state, "ส่งคำสั่งแล้ว: " + cmd);
        } catch (Exception e) { publish("ERROR", "ส่งคำสั่งไม่สำเร็จ: " + e.getMessage()); }
    }
    private void enforceAppBan(String line) {
        java.util.regex.Matcher match=java.util.regex.Pattern.compile("(?i)Player connected:\\s*([^,\\r\\n]+)").matcher(line);
        if(!match.find())return;
        String player=match.group(1).trim();
        java.util.Set<String> bans=getSharedPreferences("player_bans",MODE_PRIVATE).getStringSet("names",java.util.Collections.emptySet());
        for(String banned:bans)if(banned.equalsIgnoreCase(player)){
            OutputStream out=stdin;
            try{if(out!=null){out.write(("kick \""+player.replace("\"","")+"\" Banned by BedrockPocket\\n").getBytes(StandardCharsets.UTF_8));out.flush();ServerInstaller.appendLog(new File(getFilesDir(),"app.log"),"แบนผู้เล่นซ้ำและเตะออก: "+player);}}
            catch(Exception e){ServerInstaller.appendLog(new File(getFilesDir(),"app.log"),"บังคับแบนไม่สำเร็จ: "+e.getMessage());}
            break;
        }
    }
private void stopServer() {
        Process p = process;
        if (p == null || !p.isAlive()) {
            publish(ServerInstaller.isInstalled(new File(getFilesDir(), "bds")) ? "INSTALLED" : "STOPPED", "BDS หยุดอยู่แล้ว");
            stopForeground(true); stopSelf(); return;
        }
        getSharedPreferences("server",MODE_PRIVATE).edit().putBoolean("user_stop_requested",true).apply();
        publish("STOPPING", "ส่งคำสั่ง stop ให้ Mojang BDS");
        try { OutputStream out = stdin; if (out != null) { out.write("stop\n".getBytes(StandardCharsets.UTF_8)); out.flush(); } }
        catch (Exception e) { ServerInstaller.appendLog(new File(getFilesDir(), "app.log"), "ส่ง stop ไม่สำเร็จ: " + e.getMessage()); }
        new Thread(() -> {
            try { if (!p.waitFor(20, TimeUnit.SECONDS)) p.destroy(); if (p.isAlive()) p.destroyForcibly(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); p.destroyForcibly(); }
        }, "BDS-Stop").start();
    }

    private Notification notification(String text) {
        String channel = "bds_server";
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(new NotificationChannel(channel, "Minecraft BDS", NotificationManager.IMPORTANCE_LOW));
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open,
                (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0) | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, channel) : new Notification.Builder(this);
        return builder.setContentTitle("Minecraft Bedrock Dedicated Server").setContentText(text).setSmallIcon(android.R.drawable.stat_notify_sync).setContentIntent(pending).setOngoing(true).build();
    }

    private void publish(String newState, String message) {
        String previousState = state;
        state = newState;
        getSharedPreferences("server", MODE_PRIVATE).edit().putString("server_state", newState)
                .putString("server_message", message == null ? "" : message).putLong("server_state_at", System.currentTimeMillis()).apply();
        Intent update = new Intent(ACTION_STATE);
        update.setPackage(getPackageName());
        update.putExtra(EXTRA_STATE, newState);
        update.putExtra(EXTRA_MESSAGE, message);
        sendBroadcast(update);
        if ("RUNNING".equals(newState) || "INSTALLING".equals(newState) || "STARTING".equals(newState)) {
            try { ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION_ID, notification(message)); } catch (Exception ignored) {}
        }
        if ("ERROR".equals(newState) && !"ERROR".equals(previousState)) notifyServerProblem(message);
    }

    private void notifyServerProblem(String message) {
        try {
            String channel = "bds_alerts";
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager == null) return;
            if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(new NotificationChannel(channel, "แจ้งเตือนเซิร์ฟเวอร์", NotificationManager.IMPORTANCE_DEFAULT));
            Notification.Builder builder = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, channel) : new Notification.Builder(this);
            manager.notify(NOTIFICATION_ID + 1, builder.setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentTitle("Minecraft BDS หยุดหรือเริ่มไม่สำเร็จ")
                    .setContentText(message == null || message.isEmpty() ? "เปิด BedrockPocket เพื่อดูบันทึก" : message)
                    .setAutoCancel(true).build());
        } catch (Exception ignored) {}
    }

    private void releaseWakeLock() {
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) {}
        wakeLock = null;
    }
    @Override public void onDestroy() { Process p = process; if (p != null && p.isAlive()) p.destroy(); metricsRootPid = -1; releaseWakeLock(); super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}
