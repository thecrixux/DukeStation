package com.sbro.emucorer.pocket;

import android.util.AtomicFile;
import java.io.*;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;

/** Offline whole-card exchange. Caller must hold CoreRuntime's stopped-session gate. */
final class PocketCardLink {
    static final int CARD_SIZE = 128 * 1024;
    static final class Conflict extends IOException { Conflict() { super("PS1 card changed since import"); } }
    static byte[] read(File file) throws IOException { return readStream(new AtomicFile(file).openRead()); }
    // Never apply Android AtomicFile's legacy .bak recovery to a GooseStation-owned file.
    static byte[] readSource(File file) throws IOException { return readStream(new FileInputStream(file)); }
    private static byte[] readStream(InputStream stream) throws IOException {
        try(InputStream in=stream;ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] chunk=new byte[8192];int n;
            while((n=in.read(chunk))!=-1){if(out.size()+n>CARD_SIZE)throw new IOException("Invalid card size");out.write(chunk,0,n);}
            byte[] bytes=out.toByteArray();validate(bytes);return bytes;
        }
    }
    static void validate(byte[] bytes) throws IOException {
        if(bytes==null||bytes.length!=CARD_SIZE||bytes[0]!='M'||bytes[1]!='C')throw new IOException("Expected a raw PS1 memory card (128 KiB, MC header)");
    }
    static void write(File file, byte[] bytes) throws IOException {
        AtomicFile atomic=new AtomicFile(file);FileOutputStream out=null;
        try{out=atomic.startWrite();out.write(bytes);atomic.finishWrite(out);}catch(IOException e){if(out!=null)atomic.failWrite(out);throw e;}
    }
    private static void writeSource(File source,byte[] bytes) throws IOException {
        File staged=File.createTempFile("dukepocket-", ".pending", source.getParentFile());
        try {
            try(FileOutputStream out=new FileOutputStream(staged)){out.write(bytes);out.getFD().sync();}
            // If this filesystem cannot atomically replace the file, fail without a destructive fallback.
            Files.move(staged.toPath(),source.toPath(),java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(staged.toPath()); }
    }
    static String hash(byte[] bytes) {
        try{byte[] digest=MessageDigest.getInstance("SHA-256").digest(bytes);StringBuilder s=new StringBuilder();for(byte b:digest)s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();}
        catch(java.security.NoSuchAlgorithmException e){throw new AssertionError(e);}
    }
    static File metadata(File card){return new File(card.getPath()+".link");}
    static Properties link(File card) throws IOException {
        Properties p=new Properties();try(InputStream in=new AtomicFile(metadata(card)).openRead()){p.load(in);}return p;
    }
    private static void saveLink(File card,Properties p) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();p.store(out,"DukePocket offline PS1 card link");write(metadata(card),out.toByteArray());
    }
    static boolean isLinked(File card){return card!=null&&(metadata(card).exists()||new File(metadata(card)+".bak").exists());}
    static File checkedSource(File source,String[] roots) throws IOException {
        File canonical=source.getCanonicalFile();boolean allowed=false;
        for(String root:roots){String prefix=new File(root).getCanonicalPath()+File.separator;if(canonical.getPath().startsWith(prefix)){allowed=true;break;}}
        if(!allowed||!canonical.isFile())throw new IOException("PS1 card is no longer in the active DukeStation save folders");
        return canonical;
    }
    static List<File> discover(String[] roots) throws IOException {
        TreeMap<String,File> cards=new TreeMap<>();int[] budget={4096};Set<String> seen=new HashSet<>();
        for(String root:roots)scan(new File(root),roots,4,budget,seen,cards);
        ArrayList<File> result=new ArrayList<>(cards.values());result.sort((a,b)->a.getName().compareToIgnoreCase(b.getName()));return result;
    }
    private static void scan(File dir,String[] roots,int depth,int[] budget,Set<String> seen,Map<String,File> cards)throws IOException{
        if(depth<0||budget[0]<=0||!dir.isDirectory()||!seen.add(dir.getCanonicalPath()))return;
        File[] files=dir.listFiles();if(files==null)return;
        for(File f:files){if(--budget[0]<0)return;if(Files.isSymbolicLink(f.toPath()))continue;if(f.isDirectory()){scan(f,roots,depth-1,budget,seen,cards);continue;}
            String name=f.getName().toLowerCase(Locale.ROOT);
            if((name.endsWith(".mcd")||name.endsWith(".mcr")||name.endsWith(".srm"))&&f.length()==CARD_SIZE){try{File c=checkedSource(f,roots);readSource(c);cards.put(c.getPath(),c);}catch(IOException ignored){}}
        }
    }
    static File importCard(File source,File directory,String[] roots) throws IOException {
        source=checkedSource(source,roots);byte[] bytes=readSource(source);
        String name=source.getName().replaceAll("[^A-Za-z0-9._-]","_");if(name.length()>65)name=name.substring(0,65);
        File card=new File(directory,"PS1-"+name+"-"+UUID.randomUUID().toString().substring(0,8)+".mcd");
        write(card,bytes);write(new File(card+".original"),bytes);
        Properties p=new Properties();p.setProperty("source",source.getPath());p.setProperty("hash",hash(bytes));saveLink(card,p);return card;
    }
    static boolean hasPocketApp(byte[] card){
        for(int block=1;block<=15;block++){
            int directory=block*128; // Only allocated first blocks carry app title sectors.
            if((card[directory]&255)!=0x51)continue;
            int at=block*8192+0x52;if(card[at]=='M'&&card[at+1]=='C'&&card[at+2]=='X'&&card[at+3]=='0')return true;
        }return false;
    }
    static File commit(File card,String[] roots) throws IOException {
        Properties p=link(card);File source=checkedSource(new File(p.getProperty("source","")),roots);
        byte[] current=readSource(source),updated=read(card);
        if(!hash(current).equals(p.getProperty("hash")))throw new Conflict();
        if(Arrays.equals(current,updated))return null;
        File backup=new File(card.getParentFile(),card.getName()+".ps1-backup-"+UUID.randomUUID());
        write(backup,current);
        // Record backup before touching the original. A metadata failure aborts the operation.
        p.setProperty("backup",backup.getName());saveLink(card,p);
        if(!hash(readSource(source)).equals(p.getProperty("hash")))throw new Conflict();
        writeSource(source,updated);
        p.setProperty("hash",hash(updated));saveLink(card,p);
        return backup;
    }
    static File backup(File card) throws IOException {
        String name=link(card).getProperty("backup");if(name==null)throw new IOException("No PS1 backup yet");
        File backup=new File(card.getParentFile(),name).getCanonicalFile();
        if(!backup.getParentFile().equals(card.getParentFile().getCanonicalFile())||!backup.getName().startsWith(card.getName()+".ps1-backup-"))throw new IOException("Invalid backup path");
        read(backup);return backup;
    }
}
