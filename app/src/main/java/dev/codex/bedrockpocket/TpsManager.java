package dev.codex.bedrockpocket;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

final class TpsManager {
    static final String PACK_UUID = "5b2a0e17-2ac7-4ec8-9b29-712ba8bce23d";
    private static final String ASSET_ROOT = "tps/BedrockPocketTPS";
    private TpsManager() {}

    static void enable(Context context, File server) throws Exception {
        File properties = new File(server, "server.properties");
        requirePlainFile(properties);if (!properties.isFile()) throw new IOException("ไม่พบ server.properties");
        String level = "Bedrock level";
        for (String line : Files.readAllLines(properties.toPath(), StandardCharsets.UTF_8))
            if (line.startsWith("level-name=")) { level = line.substring("level-name=".length()).trim(); break; }
        if (level.isEmpty() || level.equals(".") || level.equals("..") || level.contains("/") || level.contains("\\"))
            throw new IOException("ชื่อโลกใน server.properties ไม่ปลอดภัย");

        File packRoot = new File(server,"behavior_packs");requirePlainDirectory(packRoot);if(!packRoot.isDirectory()&&!packRoot.mkdirs())throw new IOException("สร้างโฟลเดอร์ Behavior Pack ไม่ได้");
        File pack = new File(packRoot, "BedrockPocketTPS");requirePlainDirectory(pack);
        installBundledPack(context, pack);

        File worlds = new File(server,"worlds");requirePlainDirectory(worlds);if(!worlds.isDirectory()&&!worlds.mkdirs())throw new IOException("สร้างโฟลเดอร์ worlds ไม่ได้");File world = new File(worlds, level);requirePlainDirectory(world);
        if (!world.isDirectory() && !world.mkdirs()) throw new IOException("สร้างโฟลเดอร์โลกไม่สำเร็จ");
        File packList = new File(world, "world_behavior_packs.json");
        requirePlainFile(packList);
        JSONArray entries = readArray(packList); boolean found = false;
        for (int i=0;i<entries.length();i++) if (PACK_UUID.equalsIgnoreCase(entries.optJSONObject(i)==null?"":entries.optJSONObject(i).optString("pack_id",""))) found=true;
        if (!found) entries.put(new JSONObject().put("pack_id", PACK_UUID).put("version", new JSONArray("[1,0,0]")));
        writeJson(packList, entries.toString(2));
        File originalLogSetting=new File(server,".bedrockpocket-tps-content-log-original");
        if(!originalLogSetting.isFile())Files.write(originalLogSetting.toPath(),readProperty(properties,"content-log-console-output-enabled","false").getBytes(StandardCharsets.UTF_8));
        updateProperty(properties, "content-log-console-output-enabled", "true");
    }

    private static void installBundledPack(Context context, File pack) throws Exception {
        requirePlainDirectory(pack);
        File scripts = new File(pack, "scripts");
        requirePlainDirectory(scripts);
        if (!scripts.isDirectory() && !scripts.mkdirs()) throw new IOException("สร้างโฟลเดอร์ TPS pack ไม่ได้");

        byte[] manifestBytes = readAsset(context, ASSET_ROOT + "/manifest.json", 32 * 1024);
        byte[] scriptBytes = readAsset(context, ASSET_ROOT + "/scripts/main.js", 64 * 1024);
        JSONObject manifest;
        try { manifest = new JSONObject(new String(manifestBytes, StandardCharsets.UTF_8)); }
        catch (Exception e) { throw new IOException("ไฟล์ manifest ของตัววัด TPS ในแอปเสียหาย", e); }
        String embeddedUuid = manifest.optJSONObject("header") == null ? "" : manifest.optJSONObject("header").optString("uuid", "");
        if (!PACK_UUID.equalsIgnoreCase(embeddedUuid) || scriptBytes.length == 0) {
            throw new IOException("ไฟล์ตัววัด TPS ที่ฝังมากับแอปไม่ครบหรือไม่ถูกต้อง");
        }

        writeManagedFile(new File(pack, "manifest.json"), manifestBytes);
        writeManagedFile(new File(pack, "scripts/main.js"), scriptBytes);
        writeManagedFile(new File(pack, ".bedrockpocket-managed"), new byte[0]);
    }

    private static byte[] readAsset(Context context, String path, int maxBytes) throws IOException {
        try (java.io.InputStream input = context.getAssets().open(path);
             java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > maxBytes) throw new IOException("ไฟล์ตัววัด TPS ในแอปใหญ่เกินกำหนด");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static void writeManagedFile(File target, byte[] bytes) throws IOException {
        requirePlainFile(target);
        File temporary = new File(target.getParentFile(), target.getName() + ".tmp");
        requirePlainFile(temporary);
        Files.write(temporary.toPath(), bytes);
        try { Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING); }
        catch (IOException e) { temporary.delete(); throw e; }
    }

    static void disable(File server) throws Exception {
        File properties = new File(server, "server.properties");
        requirePlainFile(properties);if (!properties.isFile()) throw new IOException("ไม่พบ server.properties");
        String level = "Bedrock level";
        for (String line : Files.readAllLines(properties.toPath(), StandardCharsets.UTF_8))
            if (line.startsWith("level-name=")) { level = line.substring("level-name=".length()).trim(); break; }
        if(level.isEmpty()||level.equals(".")||level.equals("..")||level.contains("/")||level.contains("\\"))throw new IOException("ชื่อโลกใน server.properties ไม่ปลอดภัย");
        File worlds=new File(server,"worlds");requirePlainDirectory(worlds);File world = new File(worlds, level);requirePlainDirectory(world);
        File packList = new File(world, "world_behavior_packs.json");
        requirePlainFile(packList);
        JSONArray entries = readArray(packList), kept = new JSONArray();
        for (int i=0;i<entries.length();i++) { JSONObject entry=entries.optJSONObject(i); if(entry!=null&&!PACK_UUID.equalsIgnoreCase(entry.optString("pack_id","")))kept.put(entry); }
        writeJson(packList, kept.toString(2));
        File packRoot=new File(server,"behavior_packs");requirePlainDirectory(packRoot);AddonManager.deleteTree(new File(packRoot,"BedrockPocketTPS"));
        File originalLogSetting=new File(server,".bedrockpocket-tps-content-log-original");
        requirePlainFile(originalLogSetting);
        if(originalLogSetting.isFile()){String original=new String(Files.readAllBytes(originalLogSetting.toPath()),StandardCharsets.UTF_8).trim();updateProperty(properties,"content-log-console-output-enabled",original.equalsIgnoreCase("true")?"true":"false");originalLogSetting.delete();}
    }

    private static JSONArray readArray(File file) throws IOException {
        if (!file.isFile()) return new JSONArray();
        try { return new JSONArray(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)); }
        catch (Exception e) { throw new IOException("รายการ Behavior Pack ของโลกอ่านไม่ได้", e); }
    }
    private static void writeJson(File target, String value) throws IOException {
        File temp=new File(target.getParentFile(),target.getName()+".tmp");
        Files.write(temp.toPath(),value.getBytes(StandardCharsets.UTF_8));
        try { Files.move(temp.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING); }
        catch(IOException e){temp.delete();throw e;}
    }
    private static void updateProperty(File file,String key,String value)throws IOException {
        java.util.List<String> lines=Files.readAllLines(file.toPath(),StandardCharsets.UTF_8);java.util.ArrayList<String> output=new java.util.ArrayList<>();boolean found=false;
        for(String line:lines){if(line.startsWith(key+"=")){if(!found)output.add(key+"="+value);found=true;}else output.add(line);}
        if(!found)output.add(key+"="+value);Files.write(file.toPath(),output,StandardCharsets.UTF_8);
    }
    private static String readProperty(File file,String key,String fallback)throws IOException {for(String line:Files.readAllLines(file.toPath(),StandardCharsets.UTF_8))if(line.startsWith(key+"="))return line.substring(key.length()+1).trim();return fallback;}
    private static void requirePlainFile(File file)throws IOException{if(Files.isSymbolicLink(file.toPath()))throw new IOException("ไฟล์เซิร์ฟเวอร์เป็น symlink ที่ไม่ปลอดภัย: "+file.getName());}
    private static void requirePlainDirectory(File directory)throws IOException{if(Files.isSymbolicLink(directory.toPath()))throw new IOException("โฟลเดอร์เซิร์ฟเวอร์เป็น symlink ที่ไม่ปลอดภัย: "+directory.getName());}
}
