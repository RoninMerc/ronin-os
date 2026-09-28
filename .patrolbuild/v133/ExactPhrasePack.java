package au.com.roningroup.patrollink;

import android.content.Context;
import android.net.Uri;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * Exact recorded phrase library.
 *
 * The original master WAV can still establish/replace a whole library, but
 * small add-on WAVs can now be merged indefinitely. Each add-on contains only
 * the phrases the user wants to add or replace, separated by [pause 3].
 * No synthesis, TTS or substitute voice is used.
 */
public final class ExactPhrasePack {
    public static final int RATE = 48000;
    private static final double SILENCE_RMS = 0.0018;
    private static final double MIN_SEPARATOR_SECONDS = 1.80;
    private static final int MAX_WAV_BYTES = 512 * 1024 * 1024;

    public static final class ImportResult {
        public final int phrases,added,replaced,total;
        public final String sourceHash;
        ImportResult(int phrases,String sourceHash){this(phrases,phrases,0,phrases,sourceHash);}
        ImportResult(int phrases,int added,int replaced,int total,String sourceHash){
            this.phrases=phrases;this.added=added;this.replaced=replaced;this.total=total;this.sourceHash=sourceHash;
        }
    }

    private static final class Entry {
        final String phrase;
        final File file;
        Entry(String phrase,File file){this.phrase=phrase;this.file=file;}
    }

    private ExactPhrasePack() {}

    public static String normalize(String text) {
        if(text==null) return "";
        return text.toLowerCase(Locale.ROOT)
                .replace('–','-').replace('—','-')
                .replaceAll("[.!?]+$","")
                .replaceAll("\\s+"," ").trim();
    }

    public static File profileDir(Context context,String profileId) throws IOException {
        if(profileId==null || !profileId.matches("[A-Za-z0-9_-]{1,90}")) throw new IOException("Invalid voice profile.");
        File root=new File(context.getFilesDir(),"exact_voice_packs");
        File dir=new File(root,profileId);
        if(!dir.exists() && !dir.mkdirs()) throw new IOException("Could not create exact voice storage.");
        return dir;
    }

    public static boolean installed(Context context,String profileId) {
        try {
            File dir=profileDir(context,profileId);
            File manifest=new File(dir,"manifest.json");
            if(!manifest.isFile()) return false;
            JSONObject o=new JSONObject(readText(manifest));
            JSONArray a=o.optJSONArray("phrases");
            if(a==null || a.length()==0) return false;
            for(int i=0;i<a.length();i++) if(!clipFile(dir,i).isFile()) return false;
            return true;
        } catch(Exception e) { return false; }
    }

    public static LinkedHashMap<String,File> clips(Context context,String profileId) throws Exception {
        File dir=profileDir(context,profileId);
        List<Entry> entries=entries(dir);
        LinkedHashMap<String,File> result=new LinkedHashMap<>();
        for(Entry e:entries)result.put(normalize(e.phrase),e.file);
        return result;
    }

    public static List<String> phrases(Context context,String profileId) throws Exception {
        ArrayList<String> out=new ArrayList<>();
        for(Entry e:entries(profileDir(context,profileId)))out.add(e.phrase);
        return out;
    }

    /**
     * Full replacement import. Retained for initial setup/new profiles and for
     * users who intentionally want to rebuild the complete base library.
     */
    public static ImportResult importPack(Context context,Uri uri,String profileId,List<String> phrases) throws Exception {
        List<String> clean=validatePhraseList(phrases,3);
        File dir=profileDir(context,profileId);
        File source=copySource(context,uri,dir,"source-full-"+System.nanoTime()+".wav");
        try{
            String hash=sha256(source);
            Wav wav=Wav.read(source);
            List<long[]> segments=wav.phraseSegments();
            requireSegmentCount(segments,clean.size());

            File staging=new File(dir,"staging-full-"+System.nanoTime());
            if(!staging.mkdirs())throw new IOException("Could not prepare exact phrase clips.");
            try{
                JSONArray manifestPhrases=new JSONArray();
                for(int i=0;i<segments.size();i++){
                    long[] range=segments.get(i);
                    File clip=clipFile(staging,i);
                    wav.writeSegment(clip,range[0],range[1]);
                    validateClip(clip);
                    manifestPhrases.put(clean.get(i));
                }
                writeManifest(staging,manifestPhrases,hash);
                replaceInstalledPack(dir,staging);
            }catch(Exception e){deleteTree(staging);throw e;}
            return new ImportResult(clean.size(),clean.size(),0,clean.size(),hash);
        }finally{source.delete();}
    }

    /**
     * Merge a small WAV into the existing library.
     *
     * New phrase -> appended.
     * Existing normalized phrase -> only that recording is replaced.
     * Everything else in the existing library is copied forward untouched.
     */
    public static ImportResult importAdditions(Context context,Uri uri,String profileId,List<String> phrases) throws Exception {
        List<String> clean=validatePhraseList(phrases,1);
        File dir=profileDir(context,profileId);
        File source=copySource(context,uri,dir,"source-addon-"+System.nanoTime()+".wav");
        File additions=new File(dir,"addon-"+System.nanoTime());
        File staging=new File(dir,"staging-merge-"+System.nanoTime());
        if(!additions.mkdirs()||!staging.mkdirs()){
            deleteTree(additions);deleteTree(staging);source.delete();
            throw new IOException("Could not prepare add-on voice clips.");
        }

        try{
            String hash=sha256(source);
            Wav wav=Wav.read(source);
            List<long[]> segments=wav.phraseSegments();
            requireSegmentCount(segments,clean.size());

            LinkedHashMap<String,Entry> merged=new LinkedHashMap<>();
            if(installed(context,profileId)){
                for(Entry e:entries(dir))merged.put(normalize(e.phrase),e);
            }

            int added=0,replaced=0;
            for(int i=0;i<clean.size();i++){
                File clip=new File(additions,String.format(Locale.ROOT,"new-%04d.wav",i));
                long[] range=segments.get(i);
                wav.writeSegment(clip,range[0],range[1]);
                validateClip(clip);

                String phrase=clean.get(i),key=normalize(phrase);
                if(merged.containsKey(key))replaced++;else added++;
                merged.put(key,new Entry(phrase,clip));
            }

            JSONArray manifestPhrases=new JSONArray();
            int index=0;
            for(Entry e:merged.values()){
                File target=clipFile(staging,index++);
                copyFile(e.file,target);
                validateClip(target);
                manifestPhrases.put(e.phrase);
            }
            writeManifest(staging,manifestPhrases,hash);
            replaceInstalledPack(dir,staging);
            return new ImportResult(clean.size(),added,replaced,merged.size(),hash);
        }finally{
            source.delete();
            deleteTree(additions);
            deleteTree(staging);
        }
    }

    private static List<Entry> entries(File dir)throws Exception{
        File manifestFile=new File(dir,"manifest.json");
        if(!manifestFile.isFile())throw new IOException("Exact voice library is not installed.");
        JSONObject o=new JSONObject(readText(manifestFile));
        JSONArray a=o.getJSONArray("phrases");
        ArrayList<Entry> result=new ArrayList<>();
        for(int i=0;i<a.length();i++){
            File file=clipFile(dir,i);
            if(!file.isFile())throw new IOException("Exact voice library is incomplete.");
            result.add(new Entry(a.getString(i),file));
        }
        return result;
    }

    private static List<String> validatePhraseList(List<String> phrases,int minimum)throws IOException{
        if(phrases==null||phrases.size()<minimum)
            throw new IOException(minimum==1?"Enter at least one phrase before importing the add-on WAV.":"Copy the phrase-pack script first so Patrol Link knows what to map.");
        ArrayList<String> clean=new ArrayList<>();
        LinkedHashSet<String> seen=new LinkedHashSet<>();
        for(String raw:phrases){
            String phrase=raw==null?"":raw.replaceAll("\\s+"," ").trim();
            if(phrase.isEmpty())throw new IOException("The phrase list contains a blank entry.");
            if(phrase.length()>300)throw new IOException("Keep each exact voice phrase under 300 characters.");
            String key=normalize(phrase);
            if(!seen.add(key))throw new IOException("The phrase list contains the same phrase more than once: "+phrase);
            clean.add(phrase);
        }
        if(clean.size()>250)throw new IOException("Use at most 250 phrases in one add-on WAV. You can import as many add-on WAVs as needed.");
        return clean;
    }

    private static File copySource(Context context,Uri uri,File dir,String name)throws Exception{
        if(uri==null)throw new IOException("Choose the exact voice WAV.");
        File source=new File(dir,name);
        try(InputStream in=context.getContentResolver().openInputStream(uri);
            OutputStream out=new BufferedOutputStream(new FileOutputStream(source))){
            if(in==null)throw new IOException("Could not open the selected WAV.");
            byte[] b=new byte[65536];int n;long total=0;
            while((n=in.read(b))!=-1){
                total+=n;
                if(total>MAX_WAV_BYTES)throw new IOException("The WAV is larger than 512 MB.");
                out.write(b,0,n);
            }
        }catch(Exception e){source.delete();throw e;}
        return source;
    }

    private static void requireSegmentCount(List<long[]> segments,int phrases)throws IOException{
        if(segments.size()!=phrases)
            throw new IOException("Patrol Link found "+segments.size()+" recorded phrases but your phrase list contains "+phrases+". Generate exactly the copied lines in the same order, including every [pause 3] separator.");
    }

    private static File clipFile(File dir,int index){
        return new File(dir,String.format(Locale.ROOT,"clip-%04d.wav",index));
    }

    private static void validateClip(File f)throws IOException{
        if(!f.isFile()||f.length()<1500)throw new IOException("A recorded phrase was empty.");
    }

    private static void writeManifest(File dir,JSONArray phrases,String sourceHash)throws Exception{
        JSONObject manifest=new JSONObject();
        manifest.put("version",2);
        manifest.put("source_sha256",sourceHash);
        manifest.put("phrases",phrases);
        writeText(new File(dir,"manifest.json"),manifest.toString(2));
    }

    private static void replaceInstalledPack(File dir,File staging)throws Exception{
        File backup=new File(dir,"backup-"+System.nanoTime());
        if(!backup.mkdirs())throw new IOException("Could not prepare voice-library backup.");

        boolean committed=false;
        try{
            File[] current=dir.listFiles((d,n)->n.startsWith("clip-")||n.equals("manifest.json"));
            if(current!=null)for(File f:current){
                if(!f.renameTo(new File(backup,f.getName())))
                    throw new IOException("Could not back up the current exact voice library.");
            }

            File[] fresh=staging.listFiles();
            if(fresh!=null)for(File f:fresh){
                if(!f.renameTo(new File(dir,f.getName())))
                    throw new IOException("Could not install the updated exact voice library.");
            }
            committed=true;
        }finally{
            if(!committed){
                File[] partial=dir.listFiles((d,n)->n.startsWith("clip-")||n.equals("manifest.json"));
                if(partial!=null)for(File f:partial)f.delete();
                File[] old=backup.listFiles();
                if(old!=null)for(File f:old)f.renameTo(new File(dir,f.getName()));
            }
            deleteTree(backup);
            deleteTree(staging);
        }
    }

    private static void copyFile(File from,File to)throws IOException{
        try(InputStream in=new BufferedInputStream(new FileInputStream(from));
            OutputStream out=new BufferedOutputStream(new FileOutputStream(to))){
            byte[] b=new byte[65536];int n;
            while((n=in.read(b))!=-1)out.write(b,0,n);
        }
    }

    private static final class Wav {
        final File file; final int channels,rate,bits,format,blockAlign; final long dataOffset,dataBytes;
        Wav(File file,int channels,int rate,int bits,int format,int blockAlign,long dataOffset,long dataBytes){
            this.file=file;this.channels=channels;this.rate=rate;this.bits=bits;this.format=format;this.blockAlign=blockAlign;this.dataOffset=dataOffset;this.dataBytes=dataBytes;
        }

        static Wav read(File file)throws IOException{
            try(RandomAccessFile f=new RandomAccessFile(file,"r")){
                if(f.length()<44||!tag(f).equals("RIFF"))throw new IOException("Export the phrase pack as a PCM WAV.");
                u32(f);if(!tag(f).equals("WAVE"))throw new IOException("Not a WAV recording.");
                int format=0,channels=0,rate=0,bits=0,align=0;long offset=-1,size=0;
                while(f.getFilePointer()+8<=f.length()){
                    String chunk=tag(f);long n=u32(f),start=f.getFilePointer();
                    if(n<0||start+n>f.length())throw new IOException("The WAV is incomplete.");
                    if(chunk.equals("fmt ")){format=u16(f);channels=u16(f);rate=(int)u32(f);u32(f);align=u16(f);bits=u16(f);}
                    else if(chunk.equals("data")){offset=start;size=n;break;}
                    f.seek(start+n+(n&1));
                }
                if(offset<0||format!=1||bits!=16||channels<1||channels>2||rate<16000||rate>96000||align!=channels*2)
                    throw new IOException("Use a 16-bit PCM WAV export (mono or stereo).");
                return new Wav(file,channels,rate,bits,format,align,offset,size);
            }
        }

        List<long[]> phraseSegments()throws IOException{
            int frames=(int)Math.min(Integer.MAX_VALUE,dataBytes/blockAlign);
            int frame10=Math.max(1,rate/100);
            int minSilentFrames=(int)Math.round(MIN_SEPARATOR_SECONDS*rate);
            ArrayList<long[]> silence=new ArrayList<>();
            try(RandomAccessFile f=new RandomAccessFile(file,"r")){
                f.seek(dataOffset);byte[] block=new byte[frame10*blockAlign];
                int frame=0,silentStart=-1;
                while(frame<frames){
                    int wanted=Math.min(block.length,(frames-frame)*blockAlign);
                    f.readFully(block,0,wanted);
                    int gotFrames=wanted/blockAlign;double e=0;
                    for(int i=0;i<gotFrames;i++){
                        double sum=0;
                        for(int ch=0;ch<channels;ch++){
                            int p=i*blockAlign+ch*2;
                            short v=(short)((block[p]&255)|((block[p+1]&255)<<8));
                            sum+=v/32768.0;
                        }
                        double mono=sum/channels;e+=mono*mono;
                    }
                    double rms=Math.sqrt(e/Math.max(1,gotFrames));
                    if(rms<SILENCE_RMS){if(silentStart<0)silentStart=frame;}
                    else if(silentStart>=0){
                        if(frame-silentStart>=minSilentFrames)silence.add(new long[]{silentStart,frame});
                        silentStart=-1;
                    }
                    frame+=gotFrames;
                }
                if(silentStart>=0&&frames-silentStart>=minSilentFrames)silence.add(new long[]{silentStart,frames});
            }

            ArrayList<Long> boundaries=new ArrayList<>();
            for(long[] s:silence){
                if(s[0]>rate/2 && s[1]<frames-rate/2)boundaries.add((s[0]+s[1])/2);
            }
            ArrayList<long[]> result=new ArrayList<>();
            long start=0;
            for(long b:boundaries){
                long[] trimmed=trim(start,b);
                if(trimmed[1]>trimmed[0])result.add(trimmed);
                start=b;
            }
            long[] last=trim(start,frames);
            if(last[1]>last[0])result.add(last);
            return result;
        }

        private long[] trim(long start,long end)throws IOException{
            long margin=rate/20,first=end,last=start;
            int chunk=Math.max(1,rate/100);
            try(RandomAccessFile f=new RandomAccessFile(file,"r")){
                for(long at=start;at<end;at+=chunk){
                    long to=Math.min(end,at+chunk);
                    if(rms(f,at,to)>=SILENCE_RMS*1.5){first=at;break;}
                }
                for(long at=end;at>start;at-=chunk){
                    long from=Math.max(start,at-chunk);
                    if(rms(f,from,at)>=SILENCE_RMS*1.5){last=at;break;}
                }
            }
            if(first>=end||last<=start)return new long[]{0,0};
            return new long[]{Math.max(start,first-margin),Math.min(end,last+margin)};
        }

        private double rms(RandomAccessFile f,long a,long b)throws IOException{
            int frames=(int)(b-a);if(frames<=0)return 0;
            byte[] x=new byte[frames*blockAlign];
            f.seek(dataOffset+a*blockAlign);f.readFully(x);
            double e=0;
            for(int i=0;i<frames;i++){
                double sum=0;
                for(int ch=0;ch<channels;ch++){
                    int p=i*blockAlign+ch*2;
                    short v=(short)((x[p]&255)|((x[p+1]&255)<<8));
                    sum+=v/32768.0;
                }
                double m=sum/channels;e+=m*m;
            }
            return Math.sqrt(e/frames);
        }

        void writeSegment(File out,long startFrame,long endFrame)throws IOException{
            int frames=(int)(endFrame-startFrame),bytes=frames*blockAlign;
            try(RandomAccessFile in=new RandomAccessFile(file,"r");
                DataOutputStream d=new DataOutputStream(new BufferedOutputStream(new FileOutputStream(out)))){
                d.writeBytes("RIFF");le32(d,36+bytes);d.writeBytes("WAVEfmt ");le32(d,16);
                le16(d,1);le16(d,channels);le32(d,rate);le32(d,rate*blockAlign);
                le16(d,blockAlign);le16(d,16);d.writeBytes("data");le32(d,bytes);
                in.seek(dataOffset+startFrame*blockAlign);
                byte[] buf=new byte[65536];int left=bytes;
                while(left>0){
                    int n=in.read(buf,0,Math.min(buf.length,left));
                    if(n<0)throw new EOFException();
                    d.write(buf,0,n);left-=n;
                }
            }
        }
    }

    private static int u16(RandomAccessFile f)throws IOException{return f.readUnsignedByte()|(f.readUnsignedByte()<<8);}
    private static long u32(RandomAccessFile f)throws IOException{return (long)u16(f)|((long)u16(f)<<16);}
    private static String tag(RandomAccessFile f)throws IOException{byte[] b=new byte[4];f.readFully(b);return new String(b,StandardCharsets.US_ASCII);}
    private static void le16(DataOutputStream d,int v)throws IOException{d.writeByte(v);d.writeByte(v>>>8);}
    private static void le32(DataOutputStream d,int v)throws IOException{le16(d,v);le16(d,v>>>16);}
    private static String readText(File f)throws IOException{try(InputStream in=new FileInputStream(f)){ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] x=new byte[8192];int n;while((n=in.read(x))!=-1)b.write(x,0,n);return b.toString("UTF-8");}}
    private static void writeText(File f,String s)throws IOException{try(OutputStream out=new FileOutputStream(f)){out.write(s.getBytes(StandardCharsets.UTF_8));}}
    private static String sha256(File f)throws Exception{MessageDigest md=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(f)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)md.update(b,0,n);}StringBuilder s=new StringBuilder();for(byte b:md.digest())s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();}
    private static void deleteTree(File f){if(f==null||!f.exists())return;if(f.isDirectory()){File[] c=f.listFiles();if(c!=null)for(File x:c)deleteTree(x);}f.delete();}
}
