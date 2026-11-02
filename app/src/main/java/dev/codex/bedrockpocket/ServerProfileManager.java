package dev.codex.bedrockpocket;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Profiles isolate BDS data while sharing the Ubuntu/Box64 runtime. */
final class ServerProfileManager {
    private static final String PREFS="server_profiles";
    private static final String ACTIVE="active_id";
    private static final String DEFAULT_ID="default";
    static final class Profile { final String id,name; Profile(String id,String name){this.id=id;this.name=name;} }
    private ServerProfileManager(){}

    static synchronized List<Profile> list(Context context)throws IOException {
        ensure(context);ArrayList<Profile> result=new ArrayList<>();
        try {JSONArray a=new JSONArray(new String(Files.readAllBytes(index(context).toPath()),StandardCharsets.UTF_8));for(int i=0;i<a.length();i++){JSONObject o=a.optJSONObject(i);if(o!=null){String id=o.optString("id","");String name=o.optString("name","");if(validId(id)&&!name.trim().isEmpty())result.add(new Profile(id,name));}}}
        catch(Exception e){throw new IOException("อ่านรายการโปรไฟล์ไม่ได้",e);}
        result.sort(Comparator.comparing(p->p.name.toLowerCase(java.util.Locale.ROOT)));return result;
    }
    static synchronized void ensure(Context context)throws IOException {
        File f=index(context);if(f.isFile())return;File parent=f.getParentFile();if(!parent.isDirectory()&&!parent.mkdirs())throw new IOException("สร้างรายการโปรไฟล์ไม่ได้");JSONArray a=new JSONArray();try{a.put(new JSONObject().put("id",DEFAULT_ID).put("name","เซิร์ฟเวอร์หลัก"));Files.write(f.toPath(),a.toString().getBytes(StandardCharsets.UTF_8));}catch(Exception e){throw new IOException("ตั้งค่าโปรไฟล์เริ่มต้นไม่ได้",e);}
        context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putString(ACTIVE,DEFAULT_ID).apply();
    }
    static String activeId(Context c){String id=c.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString(ACTIVE,DEFAULT_ID);return validId(id)?id:DEFAULT_ID;}
    static synchronized void setActive(Context c,String id)throws IOException {boolean found=false;for(Profile p:list(c))if(p.id.equals(id))found=true;if(!found)throw new IOException("ไม่พบโปรไฟล์เซิร์ฟเวอร์");c.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putString(ACTIVE,id).apply();}
    static File runtimeRoot(Context c){return new File(c.getFilesDir(),"bds");}
    static File activeServerDir(Context c){return serverDir(c,activeId(c));}
    static File serverDir(Context c,String id){return DEFAULT_ID.equals(id)?new File(runtimeRoot(c),"ubuntu/opt/bds"):new File(new File(c.getFilesDir(),"server-profiles"),id);}
    static String guestWorkingDirectory(String id){return DEFAULT_ID.equals(id)?"/opt/bds":"/mnt/bp_profile";}
    static synchronized Profile create(Context c,String rawName)throws Exception {
        String name=validateName(rawName);if(!ServerInstaller.isInstalled(runtimeRoot(c)))throw new IOException("ติดตั้ง runtime ก่อนสร้างโปรไฟล์เพิ่ม");
        String id=UUID.randomUUID().toString().replace("-","").substring(0,12);File target=serverDir(c,id);File template=serverDir(c,DEFAULT_ID);if(!template.isDirectory())throw new IOException("ไม่พบไฟล์ BDS สำหรับคัดลอกโปรไฟล์");
        copyTemplate(template,target);try{JSONArray a=readIndex(c);a.put(new JSONObject().put("id",id).put("name",name));writeIndex(c,a);return new Profile(id,name);}catch(Exception e){AddonManager.deleteTree(target);throw e;}
    }
    static synchronized void rename(Context c,String id,String rawName)throws Exception {String name=validateName(rawName);JSONArray a=readIndex(c);boolean found=false;for(int i=0;i<a.length();i++){JSONObject o=a.optJSONObject(i);if(o!=null&&id.equals(o.optString("id"))){o.put("name",name);found=true;}}if(!found)throw new IOException("ไม่พบโปรไฟล์เซิร์ฟเวอร์");writeIndex(c,a);}
    static synchronized void delete(Context c,String id)throws Exception {if(DEFAULT_ID.equals(id))throw new IOException("เก็บโปรไฟล์หลักไว้เสมอ");if(activeId(c).equals(id))throw new IOException("สลับไปโปรไฟล์อื่นก่อนลบ");JSONArray a=readIndex(c),next=new JSONArray();boolean found=false;for(int i=0;i<a.length();i++){JSONObject o=a.optJSONObject(i);if(o==null)continue;if(id.equals(o.optString("id")))found=true;else next.put(o);}if(!found)throw new IOException("ไม่พบโปรไฟล์เซิร์ฟเวอร์");AddonManager.deleteTree(serverDir(c,id));writeIndex(c,next);}
    static String name(Context c,String id){try{for(Profile p:list(c))if(p.id.equals(id))return p.name;}catch(Exception ignored){}return "เซิร์ฟเวอร์หลัก";}
    private static void copyTemplate(File source,File target)throws IOException {if(!target.mkdirs())throw new IOException("สร้างโฟลเดอร์โปรไฟล์ไม่ได้");File[] children=source.listFiles();if(children==null)throw new IOException("อ่านไฟล์เซิร์ฟเวอร์หลักไม่ได้");for(File child:children){if("worlds".equals(child.getName()))continue;if(java.nio.file.Files.isSymbolicLink(child.toPath()))throw new IOException("เทมเพลตมี symlink ที่ไม่ปลอดภัย");copyTree(child,new File(target,child.getName()));}}
    private static void copyTree(File source,File target)throws IOException {if(java.nio.file.Files.isSymbolicLink(source.toPath()))throw new IOException("เทมเพลตมี symlink ที่ไม่ปลอดภัย");if(source.isDirectory()){if(!target.mkdirs())throw new IOException("สร้างโฟลเดอร์โปรไฟล์ไม่ได้");File[] children=source.listFiles();if(children==null)throw new IOException("อ่านไฟล์เทมเพลตไม่ได้");for(File child:children)copyTree(child,new File(target,child.getName()));}else Files.copy(source.toPath(),target.toPath());}
    private static String validateName(String raw)throws IOException {String n=raw==null?"":raw.trim();if(n.isEmpty()||n.length()>40||n.matches(".*[\\\\/:*?\"<>|].*"))throw new IOException("ชื่อโปรไฟล์ต้องมี 1–40 ตัวอักษรและไม่มีอักขระพาธ");return n;}
    private static boolean validId(String id){return id!=null&&(DEFAULT_ID.equals(id)||id.matches("[a-f0-9]{12}"));}
    private static File index(Context c){return new File(c.getFilesDir(),"server-profiles.json");}
    private static JSONArray readIndex(Context c)throws IOException{ensure(c);try{return new JSONArray(new String(Files.readAllBytes(index(c).toPath()),StandardCharsets.UTF_8));}catch(Exception e){throw new IOException("อ่านรายการโปรไฟล์ไม่ได้",e);}}
    private static void writeIndex(Context c,JSONArray a)throws IOException{File f=index(c),tmp=new File(f.getParentFile(),f.getName()+".tmp");try{Files.write(tmp.toPath(),a.toString().getBytes(StandardCharsets.UTF_8));try{Files.move(tmp.toPath(),f.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING,java.nio.file.StandardCopyOption.ATOMIC_MOVE);}catch(java.nio.file.AtomicMoveNotSupportedException e){Files.move(tmp.toPath(),f.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);}}catch(Exception e){throw new IOException("บันทึกรายการโปรไฟล์ไม่ได้",e);}}
}
