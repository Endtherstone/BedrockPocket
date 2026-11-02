package dev.codex.bedrockpocket;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Safe world operations for the single local BDS instance. Call only while BDS is stopped. */
final class WorldManager {
    static final long MAX_ARCHIVE_BYTES = 8L * 1024L * 1024L * 1024L;
    static final int MAX_ARCHIVE_ENTRIES = 100000;
    private static final long MAX_COPY_BYTES = 8L * 1024L * 1024L * 1024L;

    static final class WorldInfo {
        final String name;
        final File directory;
        final boolean active;
        final boolean ready;
        WorldInfo(String name, File directory, boolean active, boolean ready) {
            this.name = name; this.directory = directory; this.active = active; this.ready = ready;
        }
    }

    static final class TrashInfo {
        final String key;
        final String name;
        final File directory;
        TrashInfo(String key, String name, File directory) { this.key = key; this.name = name; this.directory = directory; }
    }

    private WorldManager() {}

    static List<WorldInfo> list(File server) throws IOException {
        File worlds = worldsDir(server);
        String active = activeName(server);
        ArrayList<WorldInfo> result = new ArrayList<>();
        File[] children = worlds.listFiles();
        if (children != null) for (File dir : children) {
            if (!dir.isDirectory() || Files.isSymbolicLink(dir.toPath())) continue;
            boolean ready = isReadyWorld(dir);
            if (ready || dir.getName().equals(active)) result.add(new WorldInfo(dir.getName(), dir, dir.getName().equals(active), ready));
        }
        if (active != null && result.stream().noneMatch(w -> w.name.equals(active)))
            result.add(new WorldInfo(active, new File(worlds, active), true, false));
        result.sort((a,b) -> a.active != b.active ? (a.active ? -1 : 1) : a.name.compareToIgnoreCase(b.name));
        return result;
    }

    static List<TrashInfo> listTrash(File server) throws IOException {
        File trash = trashDir(server);
        File[] children = trash.listFiles();
        ArrayList<TrashInfo> result = new ArrayList<>();
        if (children != null) for (File dir : children) {
            if (!dir.isDirectory() || Files.isSymbolicLink(dir.toPath()) || !isReadyWorld(dir)) continue;
            String key = dir.getName();
            int split = key.indexOf("__");
            String name = split >= 0 ? key.substring(split + 2) : key;
            result.add(new TrashInfo(key, name, dir));
        }
        result.sort((a,b) -> a.key.compareTo(b.key));
        return result;
    }

    static boolean isReadyWorld(File dir) {
        if (dir == null || !dir.isDirectory() || Files.isSymbolicLink(dir.toPath())) return false;
        File level = new File(dir, "level.dat"), db = new File(dir, "db");
        File[] databaseFiles = db.listFiles();
        return level.isFile() && !Files.isSymbolicLink(level.toPath()) && db.isDirectory()
                && !Files.isSymbolicLink(db.toPath()) && databaseFiles != null && databaseFiles.length > 0;
    }

    static String activeName(File server) throws IOException { return readProperty(new File(server, "server.properties"), "level-name", "Bedrock level"); }

    static void createWorld(File server, String rawName) throws IOException {
        String name = validateName(rawName);
        File worlds = worldsDir(server);
        if (new File(worlds, name).exists()) throw new IOException("มีโฟลเดอร์ชื่อนี้อยู่แล้ว");
        setActiveName(server, name); // BDS creates the world on its next start.
    }

    static void switchWorld(File server, String rawName) throws IOException {
        String name = validateName(rawName);
        File target = new File(worldsDir(server), name);
        if (!isReadyWorld(target)) throw new IOException("โลกนี้ยังไม่พร้อมหรือไม่มี level.dat และ db/");
        setActiveName(server, name);
    }

    static void renameWorld(File server, String oldName, String rawNewName) throws IOException {
        String newName = validateName(rawNewName);
        String active = activeName(server);
        File worlds = worldsDir(server), source = new File(worlds, oldName), target = new File(worlds, newName);
        if (!oldName.equals(active) && !isReadyWorld(source)) throw new IOException("ไม่พบโลกที่ต้องการเปลี่ยนชื่อ");
        if (!oldName.equals(newName) && target.exists()) throw new IOException("มีโลกชื่อนี้อยู่แล้ว");
        if (oldName.equals(newName)) return;
        if (source.exists()) {
            if (!isReadyWorld(source)) throw new IOException("โฟลเดอร์โลกไม่สมบูรณ์ จึงเปลี่ยนชื่อไม่ได้");
            move(source, target);
        }
        if (oldName.equals(active)) {
            try { setActiveName(server, newName); }
            catch (IOException e) { if (target.exists()) move(target, source); throw e; }
        }
    }

    static void copyWorld(File server, String sourceName, String rawTargetName) throws IOException {
        String targetName = validateName(rawTargetName);
        File worlds = worldsDir(server), source = new File(worlds, sourceName), target = new File(worlds, targetName);
        if (!isReadyWorld(source)) throw new IOException("ไม่พบโลกต้นฉบับที่สมบูรณ์");
        if (target.exists()) throw new IOException("มีโลกชื่อนี้อยู่แล้ว");
        long[] bytes = {0};
        try { copyTree(source, target, bytes); if (!isReadyWorld(target)) throw new IOException("สำเนาโลกไม่สมบูรณ์"); }
        catch (IOException e) { if (target.exists()) try { deleteTree(target); } catch (IOException cleanup) { e.addSuppressed(cleanup); } throw e; }
    }

    static void moveToTrash(File server, String worldName) throws IOException {
        if (worldName.equals(activeName(server))) throw new IOException("เลือกโลกอื่นก่อนย้ายโลกที่กำลังใช้อยู่ไปถังขยะ");
        List<WorldInfo> worlds = list(server);
        int ready = 0; for (WorldInfo world : worlds) if (world.ready) ready++;
        if (ready <= 1) throw new IOException("ต้องเหลืออย่างน้อยหนึ่งโลกไว้บนเซิร์ฟเวอร์");
        File source = new File(worldsDir(server), worldName);
        if (!isReadyWorld(source)) throw new IOException("ไม่พบโลกที่ต้องการย้าย");
        File trash = trashDir(server);
        File target = new File(trash, System.currentTimeMillis() + "__" + worldName);
        move(source, target);
    }

    static void restoreTrash(File server, String key, String rawTargetName) throws IOException {
        String targetName = validateName(rawTargetName);
        File trash = trashDir(server), source = new File(trash, key), target = new File(worldsDir(server), targetName);
        requireInside(trash, source);
        if (!isReadyWorld(source)) throw new IOException("ไม่พบโลกในถังขยะ");
        if (target.exists()) throw new IOException("มีโลกชื่อนี้อยู่แล้ว เลือกชื่ออื่นเพื่อกู้คืน");
        move(source, target);
    }

    static void deleteTrash(File server, String key) throws IOException {
        File trash = trashDir(server), target = new File(trash, key);
        requireInside(trash, target);
        if (!isReadyWorld(target)) throw new IOException("ไม่พบโลกในถังขยะ");
        deleteTree(target);
    }

    static void exportMcworld(File world, OutputStream output) throws IOException {
        if (!isReadyWorld(world)) throw new IOException("โลกไม่สมบูรณ์ จึงส่งออกไม่ได้");
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(output))) {
            addWorldTree(zip, world, "");
        }
    }

    static File importMcworld(File archive, File server, String rawName) throws IOException {
        String name = validateName(rawName);
        File worlds = worldsDir(server), target = new File(worlds, name);
        if (target.exists()) throw new IOException("มีโลกชื่อนี้อยู่แล้ว");
        File stage = Files.createTempDirectory(server.toPath(), "mcworld-import-").toFile();
        boolean installed = false;
        try {
            String root = stage.getCanonicalPath() + File.separator;
            long expanded = 0; int entries = 0;
            try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(new FileInputStream(archive)))) {
                ZipEntry entry; byte[] buffer = new byte[32768];
                while ((entry = zip.getNextEntry()) != null) {
                    if (++entries > MAX_ARCHIVE_ENTRIES) throw new IOException("ไฟล์โลกมีรายการเกิน 100,000 รายการ");
                    String entryName = entry.getName();
                    if (hasUnsafeArchivePath(entryName))
                        throw new IOException("ไฟล์โลกมีพาธไม่ปลอดภัย");
                    File out = new File(stage, entryName);
                    String canonical = out.getCanonicalPath();
                    if (!canonical.startsWith(root)) throw new IOException("ไฟล์โลกพยายามเขียนออกนอกโฟลเดอร์");
                    if (entry.isDirectory()) {
                        if (out.exists() && !out.isDirectory()) throw new IOException("ไฟล์ ZIP มีชื่อซ้ำหรือชนกัน");
                        if (!out.isDirectory() && !out.mkdirs()) throw new IOException("สร้างโฟลเดอร์นำเข้าไม่ได้");
                    } else {
                        File parent = out.getParentFile();
                        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("สร้างโฟลเดอร์นำเข้าไม่ได้");
                        if (out.exists()) throw new IOException("ไฟล์ ZIP มีรายการซ้ำ");
                        try (FileOutputStream stream = new FileOutputStream(out)) {
                            int n; while ((n = zip.read(buffer)) > 0) {
                                expanded += n;
                                if (expanded > MAX_ARCHIVE_BYTES) throw new IOException("ไฟล์โลกใหญ่เกินขีดจำกัด 8 GB");
                                if (n > stage.getUsableSpace()) throw new IOException("พื้นที่ว่างไม่พอสำหรับนำเข้าโลกนี้");
                                stream.write(buffer, 0, n);
                            }
                        }
                    }
                }
            }
            if (!isReadyWorld(stage)) throw new IOException("ไฟล์ .mcworld ต้องมี level.dat และโฟลเดอร์ db/ ที่ระดับราก");
            move(stage, target); installed = true;
            return target;
        } finally { if (!installed && stage.exists()) deleteTree(stage); }
    }

    static String validateName(String raw) throws IOException {
        if (raw == null) throw new IOException("กรุณาใส่ชื่อโลก");
        String name = raw.trim();
        if (name.isEmpty() || name.length() > 64 || name.equals(".") || name.equals("..") || name.endsWith(".") || name.endsWith(" "))
            throw new IOException("ชื่อโลกต้องมี 1–64 ตัวอักษรและห้ามลงท้ายด้วยจุดหรือช่องว่าง");
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 32 || c == 127 || c == '/' || c == '\\' || c == ':' || c == ';' || c == '=' || c == '"' || c == '<' || c == '>' || c == '|')
                throw new IOException("ชื่อโลกมีอักขระที่ใช้ไม่ได้");
        }
        return name;
    }

    private static File worldsDir(File server) throws IOException {
        File worlds = new File(server, "worlds");
        if (!worlds.isDirectory() && !worlds.mkdirs()) throw new IOException("สร้างโฟลเดอร์ worlds ไม่สำเร็จ");
        if (Files.isSymbolicLink(worlds.toPath())) throw new IOException("โฟลเดอร์ worlds เป็น symlink จึงถูกบล็อก");
        return worlds;
    }

    private static File trashDir(File server) throws IOException {
        File trash = new File(server, "world-trash");
        if (!trash.isDirectory() && !trash.mkdirs()) throw new IOException("สร้างถังขยะโลกไม่สำเร็จ");
        if (Files.isSymbolicLink(trash.toPath())) throw new IOException("พาธถังขยะไม่ปลอดภัย");
        return trash;
    }

    private static void setActiveName(File server, String name) throws IOException {
        File properties = new File(server, "server.properties");
        if (!properties.isFile()) throw new IOException("ไม่พบ server.properties ของ BDS");
        List<String> lines = Files.readAllLines(properties.toPath(), StandardCharsets.UTF_8);
        ArrayList<String> updated = new ArrayList<>(); boolean found = false;
        for (String line : lines) {
            if (line.startsWith("level-name=")) { if (!found) updated.add("level-name=" + name); found = true; }
            else updated.add(line);
        }
        if (!found) updated.add("level-name=" + name);
        File temp = new File(server, "server.properties.worldtmp");
        Files.write(temp.toPath(), updated, StandardCharsets.UTF_8);
        try {
            try { Files.move(temp.toPath(), properties.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp.toPath(), properties.toPath(), StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException e) { temp.delete(); throw new IOException("บันทึก level-name ไม่สำเร็จ", e); }
    }

    private static String readProperty(File file, String key, String fallback) throws IOException {
        if (!file.isFile()) throw new IOException("ไม่พบ server.properties ของ BDS");
        for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) if (line.startsWith(key + "=")) return validateName(line.substring(key.length() + 1));
        return fallback;
    }

    private static void addWorldTree(ZipOutputStream zip, File directory, String prefix) throws IOException {
        if (Files.isSymbolicLink(directory.toPath())) throw new IOException("พบ symlink ในโลก จึงยกเลิกส่งออก");
        File[] children = directory.listFiles();
        if (children == null) throw new IOException("อ่านข้อมูลโลกไม่ได้: " + directory.getName());
        if (children.length == 0 && !prefix.isEmpty()) { zip.putNextEntry(new ZipEntry(prefix)); zip.closeEntry(); return; }
        for (File child : children) {
            if (Files.isSymbolicLink(child.toPath())) throw new IOException("พบ symlink ในโลก จึงยกเลิกส่งออก");
            String entry = prefix + child.getName() + (child.isDirectory() ? "/" : "");
            if (child.isDirectory()) addWorldTree(zip, child, entry);
            else {
                zip.putNextEntry(new ZipEntry(entry));
                try (InputStream in = new BufferedInputStream(new FileInputStream(child))) {
                    byte[] buffer = new byte[32768]; int n; while ((n = in.read(buffer)) > 0) zip.write(buffer, 0, n);
                }
                zip.closeEntry();
            }
        }
    }

    private static void copyTree(File source, File target, long[] total) throws IOException {
        if (Files.isSymbolicLink(source.toPath())) throw new IOException("พบ symlink ในโลก จึงยกเลิกทำสำเนา");
        if (source.isDirectory()) {
            if (!target.mkdirs()) throw new IOException("สร้างโฟลเดอร์สำเนาไม่ได้: " + target.getName());
            File[] children = source.listFiles(); if (children == null) throw new IOException("อ่านโฟลเดอร์โลกไม่ได้");
            for (File child : children) copyTree(child, new File(target, child.getName()), total);
        } else {
            total[0] += source.length(); if (total[0] > MAX_COPY_BYTES) throw new IOException("โลกใหญ่เกินขีดจำกัดทำสำเนา 8 GB");
            Files.copy(source.toPath(), target.toPath());
        }
    }

    private static void requireInside(File parent, File child) throws IOException {
        String root = parent.getCanonicalPath() + File.separator;
        if (!child.getCanonicalPath().startsWith(root) || Files.isSymbolicLink(child.toPath())) throw new IOException("พาธโลกไม่ปลอดภัย");
    }

    private static boolean hasUnsafeArchivePath(String name) {
        if (name == null || name.isEmpty() || name.startsWith("/") || name.startsWith("\\")
                || name.contains("\\") || name.indexOf('\0') >= 0 || name.indexOf(':') >= 0) return true;
        String[] parts = name.split("/", -1);
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.equals(".") || part.equals("..") || (part.isEmpty() && i != parts.length - 1)) return true;
        }
        return false;
    }

    private static void move(File source, File target) throws IOException {
        try { Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException e) { Files.move(source.toPath(), target.toPath()); }
    }

    private static void deleteTree(File file) throws IOException {
        if (Files.isSymbolicLink(file.toPath())) { Files.delete(file.toPath()); return; }
        if (!file.exists()) return;
        if (file.isDirectory()) { File[] children = file.listFiles(); if (children == null) throw new IOException("อ่านโฟลเดอร์ไม่ได้"); for (File child : children) deleteTree(child); }
        if (!file.delete()) throw new IOException("ลบไม่ได้: " + file.getName());
    }
}
