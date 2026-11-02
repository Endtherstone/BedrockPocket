package dev.codex.bedrockpocket;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/** Downloads official Playit ARM64 binaries and verifies their release checksums. */
final class PlayitManager {
    static final String VERSION = "1.2.0";
    private static final String AGENT_SHA256 = "3a6d69c348803c7041c49312345c18b1e9cb7dd7fc2adfbf2217e50d0d7c9a34";
    private static final String CLI_SHA256 = "d4cc2cd2e6f4bc9614a2b764eba60a5e0656d71ff8a2720a061e2333daef8940";
    private static final String RELEASE = "https://github.com/playit-cloud/playit-agent/releases/download/v1.2.0/";
    private static final long MAX_BYTES = 20L * 1024 * 1024;

    private PlayitManager() {}

    static File install(File appFiles, java.util.function.Consumer<String> progress) throws Exception {
        return installBinary(appFiles, "playit-linux-aarch64", AGENT_SHA256, progress);
    }

    static File installCli(File appFiles, java.util.function.Consumer<String> progress) throws Exception {
        return installBinary(appFiles, "playit-cli-linux-aarch64", CLI_SHA256, progress);
    }

    private static File installBinary(File appFiles, String filename, String expectedSha, java.util.function.Consumer<String> progress) throws Exception {
        File directory = new File(appFiles, "bds/playit");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("สร้างโฟลเดอร์ Playit ไม่ได้");
        File binary = new File(directory, filename);
        if (binary.isFile() && expectedSha.equalsIgnoreCase(sha256(binary))) {
            binary.setExecutable(true, true);
            return binary;
        }
        File temp = new File(directory, filename + ".part");
        HttpURLConnection connection = null;
        try {
            progress.accept("ดาวน์โหลด " + filename + " v" + VERSION + " จาก GitHub Release ทางการ");
            connection = (HttpURLConnection) new URL(RELEASE + filename).openConnection();
            connection.setConnectTimeout(20000);
            connection.setReadTimeout(30000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "BedrockPocket/0.8.29");
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) throw new IOException("ดาวน์โหลด " + filename + " ไม่สำเร็จ (HTTP " + status + ")");
            long declared = connection.getContentLengthLong();
            if (declared > MAX_BYTES) throw new IOException("ไฟล์ " + filename + " ใหญ่เกินขนาดที่กำหนด");
            long total = 0;
            try (InputStream in = connection.getInputStream(); FileOutputStream out = new FileOutputStream(temp)) {
                byte[] buffer = new byte[32768];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    total += read;
                    if (total > MAX_BYTES) throw new IOException("ไฟล์ " + filename + " ใหญ่เกินขนาดที่กำหนด");
                    out.write(buffer, 0, read);
                }
                out.getFD().sync();
            }
            if (total == 0 || !expectedSha.equalsIgnoreCase(sha256(temp))) throw new IOException("ตรวจ SHA-256 ของ " + filename + " ไม่ผ่าน ไฟล์ถูกปฏิเสธ");
            if (binary.exists() && !binary.delete()) throw new IOException("แทนที่ไฟล์ " + filename + " เดิมไม่ได้");
            if (!temp.renameTo(binary)) throw new IOException("บันทึกไฟล์ " + filename + " ไม่สำเร็จ");
            if (!binary.setExecutable(true, true)) throw new IOException("ตั้งสิทธิ์รัน " + filename + " ไม่สำเร็จ");
            progress.accept("ตรวจ SHA-256 ผ่าน · " + filename + " v" + VERSION + " พร้อมรัน");
            return binary;
        } finally {
            if (connection != null) connection.disconnect();
            if (temp.exists()) temp.delete();
        }
    }

    static boolean isInstalled(File appFiles) {
        File agent = new File(appFiles, "bds/playit/playit-linux-aarch64");
        try { return agent.isFile() && AGENT_SHA256.equalsIgnoreCase(sha256(agent)); }
        catch (Exception ignored) { return false; }
    }

    static boolean isCliInstalled(File appFiles) {
        File cli = new File(appFiles, "bds/playit/playit-cli-linux-aarch64");
        try { return cli.isFile() && CLI_SHA256.equalsIgnoreCase(sha256(cli)); }
        catch (Exception ignored) { return false; }
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[32768];
            int read;
            while ((read = in.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        StringBuilder result = new StringBuilder(64);
        for (byte value : digest.digest()) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
        return result.toString();
    }
}
