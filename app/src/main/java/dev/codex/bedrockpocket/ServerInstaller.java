package dev.codex.bedrockpocket;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.os.Build;
import android.system.Os;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.text.DateFormat;
import java.util.*;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class ServerInstaller {
    static final String SERVER_VERSION = "Mojang BDS 1.26.61.1";
    static final String BDS_URL = "https://www.minecraft.net/bedrockdedicatedserver/bin-linux/bedrock-server-1.26.61.1.zip";
    static final String BDS_SHA = "b54095267d55fd401de42f71ac4daf2c1747a49515ad41810781f1f9bafde26e";
    static final String UBUNTU_URL = "https://cdimage.ubuntu.com/ubuntu-base/releases/resolute/release/ubuntu-base-26.04.1-base-arm64.tar.gz";
    static final String UBUNTU_SHA = "5a1906794ced63a71a8119c3f211ef5f0bbe0a243001b4bbd41fdf80c5b219fd";
    private static final long MAX_ROOTFS_ARCHIVE = 80L * 1024 * 1024;
    private static final long MAX_BDS_ARCHIVE = 180L * 1024 * 1024;
    private static final long MAX_EXPANDED = 2500L * 1024 * 1024;
    private static final int MAX_LOG = 128 * 1024;

    private ServerInstaller() {}

    static boolean isInstalled(File base) {
        File ubuntu = new File(base, "ubuntu");
        File box64 = new File(ubuntu, "usr/bin/box64");
        File server = new File(ubuntu, "opt/bds/bedrock_server");
        File proot = new File(base, "proot/root/bin/proot");
        File marker = new File(base, "server.version");
        return box64.isFile() && server.isFile() && proot.isFile() && marker.isFile();
    }

    static boolean hasStagedBds(File stage) {
        File server = new File(stage, "ubuntu/opt/bds/bedrock_server");
        File marker = new File(stage, "ubuntu/opt/bds/.bds-extracted");
        try { return marker.isFile() && server.isFile() && isX86_64Elf(server); }
        catch (IOException e) { return false; }
    }

    static void install(File root, File cache, Context context, Consumer<String> progress) throws Exception {
        if (Build.SUPPORTED_64_BIT_ABIS == null || Build.SUPPORTED_64_BIT_ABIS.length == 0) {
            throw new IOException("BDS runtime ต้องใช้เครื่อง Android ARM64 (arm64-v8a)");
        }
        if (isInstalled(root)) { progress.accept("BDS และ runtime ติดตั้งอยู่แล้ว"); return; }
        File parent = root.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) throw new IOException("สร้างพื้นที่ติดตั้งไม่ได้");
        if (parent.getUsableSpace() < 2_500_000_000L) throw new IOException("ต้องมีพื้นที่ว่างอย่างน้อย 2.5 GB สำหรับ Ubuntu, Box64 และไฟล์ BDS");
        File stage = new File(parent, "bds-installing");
        if (!stage.isDirectory() && !stage.mkdirs()) throw new IOException("สร้างโฟลเดอร์ติดตั้งไม่ได้");
        File ubuntuArchive = new File(stage, "ubuntu.tar.gz");
        File bdsArchive = new File(stage, "bds.zip");
        File ubuntu = new File(stage, "ubuntu");
        try {
            File aptGet = new File(ubuntu, "usr/bin/apt-get");
            File dpkgStatus = new File(ubuntu, "var/lib/dpkg/status");
            File aptSources = new File(ubuntu, "etc/apt/sources.list.d/ubuntu.sources");
            if (!(aptGet.isFile() && dpkgStatus.isFile() && aptSources.isFile())) {
                progress.accept("ดาวน์โหลดและเตรียม Ubuntu ARM64 จาก Canonical");
                removeTree(ubuntu);
                downloadVerified(UBUNTU_URL, ubuntuArchive, UBUNTU_SHA, MAX_ROOTFS_ARCHIVE, "ubuntu.com");
                if (!ubuntu.mkdirs()) throw new IOException("สร้าง Linux userspace ไม่ได้");
                extractTarGz(ubuntuArchive, ubuntu, MAX_EXPANDED);
            } else {
                progress.accept("ใช้ Ubuntu ARM64 ที่เตรียมไว้ก่อนหน้า");
            }
            writeResolver(context, ubuntu);

            File prootRoot = new File(stage, "proot");
            File proot = new File(prootRoot, "root/bin/proot");
            if (!proot.isFile()) {
                progress.accept("ติดตั้ง Android PRoot runtime");
                removeTree(prootRoot);
                if (!prootRoot.mkdirs()) throw new IOException("สร้างโฟลเดอร์ PRoot ไม่ได้");
                try (InputStream in = context.getAssets().open("proot-android-aarch64.tar.gz")) {
                    extractTarGz(in, prootRoot, MAX_ROOTFS_ARCHIVE);
                }
                if (!proot.isFile()) throw new IOException("ไฟล์ PRoot ARM64 ในแอปไม่ครบ");
                proot.setExecutable(true, true);
            }

            File serverDir = new File(ubuntu, "opt/bds");
            File server = new File(serverDir, "bedrock_server");
            File bdsMarker = new File(serverDir, ".bds-extracted");
            if (!(bdsMarker.isFile() && server.isFile() && isX86_64Elf(server))) {
                progress.accept("ดาวน์โหลด Mojang Bedrock Dedicated Server");
                removeTree(serverDir);
                downloadVerified(BDS_URL, bdsArchive, BDS_SHA, MAX_BDS_ARCHIVE, "minecraft.net");
                if (!serverDir.mkdirs()) throw new IOException("สร้างโฟลเดอร์ BDS ไม่ได้");
                extractZip(bdsArchive, serverDir, MAX_EXPANDED);
                if (!server.isFile() || !isX86_64Elf(server)) throw new IOException("ไฟล์ BDS ไม่ใช่ Linux x86_64 ที่คาดไว้");
                server.setExecutable(true, true);
                writeText(new File(serverDir, "eula.txt"), "eula=true\n");
                writeText(bdsMarker, SERVER_VERSION + "\nSHA-256 verified\n");
            } else {
                progress.accept("พบ Mojang BDS ที่ดาวน์โหลดและตรวจสอบไว้แล้ว");
            }
            writeText(new File(ubuntu, "etc/hosts"), "127.0.0.1 localhost\n::1 localhost\n");

            progress.accept("เตรียม Ubuntu และ Box64 (ดาวน์โหลดแพ็กเกจเพิ่ม)");
            File prootTmp = new File(cache, "proot-tmp");
            if (!prootTmp.mkdirs() && !prootTmp.isDirectory()) throw new IOException("สร้างพื้นที่ชั่วคราวของ PRoot ไม่ได้");
            runLinux(proot, ubuntu, prootTmp, progress, "/usr/bin/apt-get", "update");
            runLinux(proot, ubuntu, prootTmp, progress, "/usr/bin/dpkg", "--add-architecture", "amd64");
            runLinux(proot, ubuntu, prootTmp, progress, "/usr/bin/apt-get", "update");
            runLinux(proot, ubuntu, prootTmp, progress, "/usr/bin/dpkg", "--configure", "-a");
            runLinux(proot, ubuntu, prootTmp, progress, "/usr/bin/apt-get", "-f", "install", "-y", "--no-install-recommends");
            runLinux(proot, ubuntu, prootTmp, progress, "/usr/bin/apt-get", "install", "-y", "--no-install-recommends", "box64");
            runLinux(proot, ubuntu, prootTmp, progress, "/usr/bin/apt-get", "install", "-y", "--no-install-recommends", "libc6:amd64", "libstdc++6:amd64", "libgcc-s1:amd64");
            File installedBox = new File(ubuntu, "usr/bin/box64");
            if (!installedBox.isFile()) throw new IOException("Ubuntu ติดตั้ง Box64 ไม่สำเร็จ");

            writeText(new File(stage, "server.version"), SERVER_VERSION + "\nUbuntu 26.04.1 ARM64 + Box64\n");
            if (ubuntuArchive.exists()) ubuntuArchive.delete();
            if (bdsArchive.exists()) bdsArchive.delete();
            removeTree(root);
            if (!stage.renameTo(root)) throw new IOException("ย้าย runtime ไปยังโฟลเดอร์ใช้งานไม่ได้");
            progress.accept("ติดตั้ง BDS และ Box64 เสร็จแล้ว");
        } catch (Exception e) {
            if (hasStagedBds(stage)) progress.accept("Mojang BDS ดาวน์โหลดและตรวจสอบแล้ว เก็บไว้ให้กดติดตั้งเพื่อทำขั้น Linux ต่อได้");
            throw e;
        }
    }
    private static void runLinux(File proot, File root, File prootTmp, Consumer<String> progress, String... command) throws Exception {
        ArrayList<String> args = new ArrayList<>();
        args.add(proot.getAbsolutePath());
        Collections.addAll(args, "-0", "--link2symlink", "-r", root.getAbsolutePath(), "-b", "/dev", "-b", "/proc", "-b", "/sys", "-w", "/");
        Collections.addAll(args, command);
        ProcessBuilder pb = new ProcessBuilder(args);
        pb.redirectErrorStream(true);
        pb.environment().put("PROOT_TMP_DIR", prootTmp.getAbsolutePath());
        pb.environment().put("HOME", "/root");
        pb.environment().put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        pb.environment().put("TERM", "xterm");
        pb.environment().put("DEBIAN_FRONTEND", "noninteractive");
        File l2s = new File(root, ".l2s");
        if (!l2s.mkdirs() && !l2s.isDirectory()) throw new IOException("สร้างพื้นที่ hard-link compatibility ไม่ได้");
        pb.environment().put("PROOT_L2S_DIR", l2s.getAbsolutePath());
        Process process = pb.start();
        ArrayDeque<String> tail = new ArrayDeque<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                progress.accept(line);
                tail.addLast(line);
                while (tail.size() > 24) tail.removeFirst();
            }
        }
        int exit = process.waitFor();
        if (exit != 0) {
            String details = String.join("\n", tail);
            throw new IOException("Linux setup command failed (exit " + exit + "): " + String.join(" ", command)
                    + (details.isEmpty() ? "" : "\nAPT output:\n" + details));
        }
    }

    private static void writeResolver(Context context, File ubuntu) throws Exception {
        StringBuilder content = new StringBuilder();
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        Network network = cm == null ? null : cm.getActiveNetwork();
        LinkProperties properties = network == null ? null : cm.getLinkProperties(network);
        if (properties != null) for (InetAddress dns : properties.getDnsServers()) content.append("nameserver ").append(dns.getHostAddress()).append('\n');
        if (content.length() == 0) throw new IOException("Android ยังไม่มีเครือข่าย DNS ที่ใช้งานได้");
        File resolver = new File(ubuntu, "etc/resolv.conf");
        File parent = resolver.getParentFile();
        if (!parent.mkdirs() && !parent.isDirectory()) throw new IOException("สร้างไฟล์ DNS ใน Ubuntu ไม่ได้");
        if (java.nio.file.Files.isSymbolicLink(resolver.toPath()) && !resolver.delete()) throw new IOException("แทนที่ symlink DNS ใน Ubuntu ไม่ได้");
        writeText(resolver, content.toString());
    }

    private static void downloadVerified(String address, File target, String expectedSha, long maxBytes, String allowedDomain) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        c.setConnectTimeout(30000); c.setReadTimeout(120000); c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "BedrockBDSAndroid/0.4.4");
        try {
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) throw new IOException("ดาวน์โหลดไม่ได้ (HTTP " + code + ")");
            String host = c.getURL().getHost().toLowerCase(Locale.ROOT);
            if (!"https".equalsIgnoreCase(c.getURL().getProtocol()) || !(host.equals(allowedDomain) || host.endsWith("." + allowedDomain) || host.equals("ubuntu.com") || host.endsWith(".ubuntu.com") || host.equals("azureedge.net") || host.endsWith(".azureedge.net"))) {
                throw new IOException("ลิงก์ดาวน์โหลดเปลี่ยนไปยังโดเมนที่ไม่อนุญาต: " + host);
            }
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            long total = 0;
            File parent = target.getParentFile(); if (parent != null) parent.mkdirs();
            try (InputStream in = new BufferedInputStream(c.getInputStream()); OutputStream out = new BufferedOutputStream(new FileOutputStream(target))) {
                byte[] b = new byte[65536]; int n;
                while ((n = in.read(b)) != -1) {
                    total += n; if (total > maxBytes) throw new IOException("ไฟล์ดาวน์โหลดใหญ่กว่าขีดจำกัด");
                    sha.update(b, 0, n); out.write(b, 0, n);
                }
            }
            if (total == 0) throw new IOException("ไฟล์ดาวน์โหลดว่างเปล่า");
            StringBuilder actual = new StringBuilder();
            for (byte value : sha.digest()) actual.append(String.format(Locale.ROOT, "%02x", value & 255));
            if (!expectedSha.equals(actual.toString())) throw new IOException("SHA-256 ไม่ตรงกับค่าที่บันทึกไว้ ไฟล์ถูกปฏิเสธ");
        } finally { c.disconnect(); }
    }

    private static void extractTarGz(File archive, File root, long maxExpanded) throws IOException {
        try (InputStream in = new BufferedInputStream(new FileInputStream(archive))) { extractTarGz(in, root, maxExpanded); }
    }

    private static void extractTarGz(InputStream source, File root, long maxExpanded) throws IOException {
        String rootPath;
        try { rootPath = root.getCanonicalPath(); } catch (IOException e) { throw e; }
        long expanded = 0;
        try (TarArchiveInputStream tar = new TarArchiveInputStream(new GZIPInputStream(new BufferedInputStream(source)))) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextTarEntry()) != null) {
                String name = entry.getName();
                if (name == null || name.isEmpty() || name.indexOf('\0') >= 0 || name.indexOf('\\') >= 0) throw new IOException("พบ path ที่ไม่ถูกต้องใน Linux archive");
                while (name.startsWith("./")) name = name.substring(2);
                if (name.startsWith("/")) throw new IOException("พบ absolute path ใน Linux archive");
                File out = new File(root, name);
                String canonical = out.getCanonicalPath();
                if (!(canonical.equals(rootPath) || canonical.startsWith(rootPath + File.separator))) throw new IOException("พบ path ออกจาก Linux rootfs");
                if (entry.isDirectory()) {
                    if (!out.mkdirs() && !out.isDirectory()) throw new IOException("สร้างไดเรกทอรีไม่ได้: " + name);
                } else if (entry.isSymbolicLink()) {
                    File parent = out.getParentFile(); if (parent == null || (!parent.mkdirs() && !parent.isDirectory())) throw new IOException("สร้าง symlink parent ไม่ได้");
                    Path rootP = root.getAbsoluteFile().toPath().normalize();
                    Path outP = out.getAbsoluteFile().toPath().normalize();
                    Path target = new File(entry.getLinkName()).isAbsolute() ? rootP.resolve(entry.getLinkName().replaceFirst("^[\\\\/]+", "")).normalize() : outP.getParent().resolve(entry.getLinkName()).normalize();
                    if (!target.startsWith(rootP)) throw new IOException("symlink พยายามออกจาก Linux rootfs: " + name);
                    String relativeTarget = outP.getParent().relativize(target).toString();
                    try { Os.symlink(relativeTarget, out.getAbsolutePath()); } catch (Exception e) { if (!new File(out.getAbsolutePath()).exists()) throw new IOException("สร้าง symlink ไม่ได้: " + name, e); }
                } else if (entry.isFile()) {
                    long size = entry.getSize();
                    if (size < 0 || size > maxExpanded || expanded + size > maxExpanded) throw new IOException("Linux archive ขยายใหญ่เกินขีดจำกัด");
                    File parent = out.getParentFile(); if (parent == null || (!parent.mkdirs() && !parent.isDirectory())) throw new IOException("สร้างไดเรกทอรีไม่ได้");
                    try (OutputStream file = new BufferedOutputStream(new FileOutputStream(out))) { copyExactly(tar, file, size); }
                    expanded += size;
                    if ((entry.getMode() & 0111) != 0) out.setExecutable(true, true);
                } else if (entry.isLink()) {
                    File parent = out.getParentFile(); if (parent == null || (!parent.mkdirs() && !parent.isDirectory())) throw new IOException("สร้างไดเรกทอรีไม่ได้");
                    File sourceFile = new File(root, entry.getLinkName());
                    if (!sourceFile.getCanonicalPath().startsWith(rootPath + File.separator)) throw new IOException("hardlink พยายามออกจาก Linux rootfs");
                    try { Os.link(sourceFile.getAbsolutePath(), out.getAbsolutePath()); }
                    catch (Exception e) { copyFile(sourceFile, out); }
                } else if (entry.isCharacterDevice() || entry.isBlockDevice() || entry.isFIFO()) {
                    continue;
                }
            }
        } catch (Exception e) {
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException("อ่าน Linux tar.gz ไม่สำเร็จ: " + e.getMessage(), e);
        }
    }

    private static void extractZip(File archive, File root, long maxExpanded) throws IOException {
        String rootPath = root.getCanonicalPath() + File.separator;
        long expanded = 0;
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(new FileInputStream(archive)))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (name.startsWith("/") || name.contains("\\") || name.contains("../") || name.equals("..")) throw new IOException("พบ path ที่ไม่ปลอดภัยใน BDS ZIP");
                File out = new File(root, name);
                if (!out.getCanonicalPath().startsWith(rootPath) && !out.getCanonicalPath().equals(root.getCanonicalPath())) throw new IOException("BDS ZIP พยายามเขียนออกจากโฟลเดอร์เซิร์ฟเวอร์");
                if (entry.isDirectory()) {
                    if (!out.mkdirs() && !out.isDirectory()) throw new IOException("สร้างโฟลเดอร์ BDS ไม่ได้");
                } else {
                    File parent = out.getParentFile(); if (parent == null || (!parent.mkdirs() && !parent.isDirectory())) throw new IOException("สร้างโฟลเดอร์ BDS ไม่ได้");
                    try (OutputStream file = new BufferedOutputStream(new FileOutputStream(out))) {
                        byte[] b = new byte[65536]; int n;
                        while ((n = zip.read(b)) != -1) { expanded += n; if (expanded > maxExpanded) throw new IOException("BDS ZIP ขยายใหญ่เกินขีดจำกัด"); file.write(b, 0, n); }
                    }
                }
                zip.closeEntry();
            }
        }
    }

    private static boolean isX86_64Elf(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) { byte[] h = new byte[20]; int n = in.read(h); return n == 20 && h[0] == 0x7f && h[1] == 'E' && h[2] == 'L' && h[3] == 'F' && h[4] == 2 && h[5] == 1 && ((h[18] & 255) | ((h[19] & 255) << 8)) == 62; }
    }
    private static void copyExactly(InputStream in, OutputStream out, long count) throws IOException {
        byte[] b = new byte[65536]; while (count > 0) { int n = in.read(b, 0, (int) Math.min(b.length, count)); if (n < 0) throw new EOFException("ไฟล์ archive ถูกตัดไม่ครบ"); out.write(b, 0, n); count -= n; }
    }
    private static void copyFile(File from, File to) throws IOException {
        try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(to)) { byte[] b = new byte[65536]; int n; while ((n = in.read(b)) != -1) out.write(b, 0, n); }
    }
    private static void writeText(File file, String content) throws IOException {
        File parent = file.getParentFile(); if (parent != null && !parent.mkdirs() && !parent.isDirectory()) throw new IOException("สร้างไฟล์ตั้งค่าไม่ได้: " + file.getName());
        try (OutputStream out = new FileOutputStream(file)) { out.write(content.getBytes(StandardCharsets.UTF_8)); }
    }
    private static void removeTree(File file) throws IOException {
        // Ubuntu rootfs contains directory symlinks such as /etc/alternatives.
        // File.isDirectory() follows them, which can recurse into their target.
        boolean symlink = java.nio.file.Files.isSymbolicLink(file.toPath());
        if (!symlink && !file.exists()) return;
        if (!symlink && file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) throw new IOException("อ่านโฟลเดอร์ชั่วคราวไม่ได้: " + file.getName());
            for (File child : children) removeTree(child);
        }
        if (!file.delete()) throw new IOException("ลบไฟล์ชั่วคราวไม่ได้: " + file.getName());
    }

    static synchronized void appendLog(File f, String line) {
        try {
            String stamp = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(new Date());
            try (FileOutputStream out = new FileOutputStream(f, true)) { out.write((stamp + "  " + line + "\n").getBytes(StandardCharsets.UTF_8)); }
            if (f.length() > MAX_LOG) { String s = readLog(f); try (FileOutputStream out = new FileOutputStream(f, false)) { out.write(s.getBytes(StandardCharsets.UTF_8)); } }
        } catch (IOException ignored) {}
    }
    static synchronized void clearLog(File f) {
        try (FileOutputStream out = new FileOutputStream(f, false)) { out.write(new byte[0]); }
        catch (IOException ignored) {}
    }
    static synchronized String readLog(File f) {
        if (!f.isFile()) return "ยังไม่มีรายการ";
        try (FileInputStream in = new FileInputStream(f)) { in.skip(Math.max(0, f.length() - MAX_LOG)); ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] b = new byte[4096]; int n; while ((n = in.read(b)) != -1) out.write(b, 0, n); return out.toString(StandardCharsets.UTF_8.name()); }
        catch (IOException e) { return "อ่านบันทึกไม่ได้"; }
    }
    static String userMessage(Exception e) {
        if (e instanceof java.net.UnknownHostException) return "เชื่อมต่อเครือข่ายไม่ได้ ตรวจอินเทอร์เน็ตแล้วลองใหม่";
        if (e instanceof java.net.SocketTimeoutException) return "การดาวน์โหลดหรือเตรียม Linux หมดเวลา ลองอีกครั้งเมื่อเครือข่ายพร้อม";
        return e.getMessage() == null ? "เกิดข้อผิดพลาดที่ไม่ทราบสาเหตุ" : e.getMessage();
    }
}
