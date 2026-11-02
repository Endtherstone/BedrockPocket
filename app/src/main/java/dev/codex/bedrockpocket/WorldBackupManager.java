package dev.codex.bedrockpocket;

import java.io.*;
import java.nio.file.*;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.*;

final class WorldBackupManager {
    private static final long MAX_RESTORE_BYTES=8L*1024L*1024L*1024L;
    private static final int MAX_RESTORE_ENTRIES=100000;
    static void create(File server,OutputStream output)throws IOException {
        File worlds=new File(server,"worlds");if(Files.isSymbolicLink(worlds.toPath())||!worlds.isDirectory())throw new IOException("ยังไม่พบโฟลเดอร์ worlds ที่ปลอดภัย");
        try(ZipOutputStream zip=new ZipOutputStream(new BufferedOutputStream(output))){addTree(zip,worlds,"worlds/");}
    }
    private static void addTree(ZipOutputStream zip,File root,String prefix)throws IOException {
        if(Files.isSymbolicLink(root.toPath()))throw new IOException("โลกมี symlink ที่ไม่ปลอดภัย");
        File[] files=root.listFiles();if(files==null)throw new IOException("อ่านโฟลเดอร์ worlds ไม่ได้");
        if(files.length==0){zip.putNextEntry(new ZipEntry(prefix));zip.closeEntry();}
        for(File f:files){if(Files.isSymbolicLink(f.toPath()))throw new IOException("โลกมี symlink ที่ไม่ปลอดภัย: "+f.getName());String name=prefix+f.getName()+(f.isDirectory()?"/":"");if(f.isDirectory())addTree(zip,f,name);else{zip.putNextEntry(new ZipEntry(name));try(InputStream in=new BufferedInputStream(new FileInputStream(f))){byte[] b=new byte[32768];int n;while((n=in.read(b))>0)zip.write(b,0,n);}zip.closeEntry();}}
    }
    static void restore(File archive,File server)throws IOException {restore(archive,server,new File(server.getParentFile(),"backups"));}
    static void restore(File archive,File server,File rollbackDirectory)throws IOException {
        File worlds=new File(server,"worlds");if(Files.isSymbolicLink(worlds.toPath()))throw new IOException("โฟลเดอร์ worlds เป็น symlink ที่ไม่ปลอดภัย");File stage=Files.createTempDirectory(server.toPath(),"restore-").toFile();
        try {String root=stage.getCanonicalPath()+File.separator;long restoredBytes=0;int entries=0;Set<String> names=new HashSet<>();try(ZipInputStream zip=new ZipInputStream(new BufferedInputStream(new FileInputStream(archive)))){ZipEntry e;byte[] b=new byte[32768];while((e=zip.getNextEntry())!=null){if(++entries>MAX_RESTORE_ENTRIES)throw new IOException("ไฟล์สำรองมีรายการมากเกินกำหนด (100,000 รายการ)");String entry=e.getName();validateEntry(entry);if(!names.add(entry))throw new IOException("ไฟล์สำรองมีรายการซ้ำ");if(!entry.equals("worlds/")&&!entry.startsWith("worlds/"))throw new IOException("ไฟล์สำรองมีไฟล์อยู่นอก worlds/");File out=new File(stage,entry);if(!out.getCanonicalPath().startsWith(root))throw new IOException("ไฟล์สำรองมีพาธไม่ปลอดภัย");if(e.isDirectory()){if(!out.mkdirs()&&!out.isDirectory())throw new IOException("สร้างโฟลเดอร์กู้คืนไม่ได้");continue;}File parent=out.getParentFile();if(!parent.isDirectory()&&!parent.mkdirs())throw new IOException("สร้างโฟลเดอร์กู้คืนไม่ได้");if(out.exists())throw new IOException("ไฟล์สำรองมีพาธชนกัน");try(FileOutputStream os=new FileOutputStream(out)){int n;while((n=zip.read(b))>0){restoredBytes+=n;if(restoredBytes>MAX_RESTORE_BYTES)throw new IOException("ไฟล์สำรองใหญ่เกินขีดจำกัดกู้คืน 8 GB");os.write(b,0,n);}}}}
            File restored=new File(stage,"worlds");if(!restored.isDirectory())throw new IOException("ไฟล์นี้ไม่มี worlds/ ที่กู้คืนได้");validateWorlds(restored);if(worlds.exists()){if(!worlds.isDirectory())throw new IOException("worlds ปัจจุบันไม่ใช่โฟลเดอร์");if(!rollbackDirectory.isDirectory()&&!rollbackDirectory.mkdirs())throw new IOException("สร้างพื้นที่เก็บโลกก่อนกู้คืนไม่ได้");File rollback=new File(rollbackDirectory,"pre-restore-"+System.currentTimeMillis()+".zip");try(OutputStream out=new BufferedOutputStream(new FileOutputStream(rollback))){create(server,out);}if(!rollback.isFile()||rollback.length()==0)throw new IOException("สำรองโลกก่อนกู้คืนไม่สำเร็จ จึงยกเลิกเพื่อรักษาโลกปัจจุบัน");}
            if(worlds.exists())AddonManager.deleteTree(worlds);
            if(!restored.renameTo(worlds))throw new IOException("ย้ายโลกที่กู้คืนไม่สำเร็จ · โลกก่อนกู้คืนเก็บไว้ใน backups/pre-restore-*.zip");
        } finally {AddonManager.deleteTree(stage);}
    }
    private static void validateWorlds(File worlds)throws IOException{File[] children=worlds.listFiles();if(children==null||children.length==0)throw new IOException("ไฟล์สำรองไม่มีโฟลเดอร์โลก");int valid=0;for(File world:children){if(!world.isDirectory())continue;File level=new File(world,"level.dat"),db=new File(world,"db");if(!level.isFile()||level.length()<8||level.length()>8L*1024L*1024L)continue;if(!db.isDirectory())continue;File[] database=db.listFiles();if(database==null||database.length==0)continue;valid++;}if(valid==0)throw new IOException("ไม่พบโลก Bedrock ที่สมบูรณ์ (ต้องมี level.dat และฐานข้อมูล db)");}
    private static void validateEntry(String name)throws IOException{if(name==null||name.isEmpty()||name.length()>4096||name.startsWith("/")||name.startsWith("\\")||name.contains("\\")||name.indexOf('\0')>=0||name.indexOf(':')>=0)throw new IOException("ไฟล์สำรองมีพาธไม่ปลอดภัย");String[] parts=name.split("/",-1);if(parts.length>64)throw new IOException("พาธในไฟล์สำรองซ้อนลึกเกินไป");for(int i=0;i<parts.length;i++)if(parts[i].equals(".")||parts[i].equals("..")||(parts[i].isEmpty()&&i!=parts.length-1))throw new IOException("ไฟล์สำรองมีพาธไม่ปลอดภัย");}
}
