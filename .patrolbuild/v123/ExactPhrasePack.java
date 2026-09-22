package au.com.roningroup.patrollink;

import android.content.Context;
import android.net.Uri;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * Exact recorded phrase pack. No voice model or synthesis is used.
 * A single AnyVoiceLab WAV generated with [pause 3] separators is split on the
 * deliberate long silences and stored as the website's original audio clips.
 */
public final class ExactPhrasePack {
    public static final int RATE = 48000;
    private static final double SILENCE_RMS = 0.0018; // ~ -55 dBFS
    private static final double MIN_SEPARATOR_SECONDS = 1.80;
    private static final int MAX_WAV_BYTES = 180 * 1024 * 1024;

    public static final class ImportResult {
        public final int phrases;
        public final String sourceHash;
        ImportResult(int phrases, String sourceHash) { this.phrases=phrases; this.sourceHash=sourceHash; }
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
            for(int i=0;i<a.length();i++) if(!new File(dir,String.format(Locale.ROOT,"clip-%04d.wav",i)).isFile()) return false;
            return true;
        } catch(Exception e) { return false; }
    }

    public static LinkedHashMap<String,File> clips(Context context,String profileId) throws Exception {
        File dir=profileDir(context,profileId);
        JSONObject o=new JSONObject(readText(new File(dir,"manifest.json")));
        JSONArray a=o.getJSONArray("phrases");
        LinkedHashMap<String,File> result=new LinkedHashMap<>();
        for(int i=0;i<a.length();i++) {
            String phrase=a.getString(i);
            File file=new File(dir,String.format(Locale.ROOT,"clip-%04d.wav",i));
            if(!file.isFile()) throw new IOException("Exact voice pack is incomplete.");
            result.put(normalize(phrase),file);
        }
        return result;
    }

    public static ImportResult importPack(Context context,Uri uri,String profileId,List<String> phrases) throws Exception {
        if(uri==null) throw new IOException("Choose the AnyVoiceLab WAV.");
        if(phrases==null || phrases.size()<3) throw new IOException("Copy the phrase-pack script first so Patrol Link knows what to map.");
        File dir=profileDir(context,profileId);
        File source=new File(dir,"source-import.wav");
        try(InputStream in=context.getContentResolver().openInputStream(uri);OutputStream out=new BufferedOutputStream(new FileOutputStream(source))) {
            if(in==null) throw new IOException("Could not open the selected WAV.");
            byte[] b=new byte[65536];int n;long total=0;
            while((n=in.read(b))!=-1){total+=n;if(total>MAX_WAV_BYTES)throw new IOException("The phrase-pack WAV is larger than 180 MB.");out.write(b,0,n);}
        }
        String hash=sha256(source);
        Wav wav=Wav.read(source);
        List<long[]> segments=wav.phraseSegments();
        if(segments.size()!=phrases.size()) {
            source.delete();
            throw new IOException("Patrol Link found "+segments.size()+" recorded phrases but the script contains "+phrases.size()+". Generate the script exactly as copied, including every [pause 3] separator, then export it as one PCM WAV.");
        }

        File staging=new File(dir,"staging-"+System.nanoTime());
        if(!staging.mkdirs()) throw new IOException("Could not prepare exact phrase clips.");
        try {
            JSONArray manifestPhrases=new JSONArray();
            for(int i=0;i<segments.size();i++) {
                long[] range=segments.get(i);
                File clip=new File(staging,String.format(Locale.ROOT,"clip-%04d.wav",i));
                wav.writeSegment(clip,range[0],range[1]);
                manifestPhrases.put(phrases.get(i));
            }
            JSONObject manifest=new JSONObject();
            manifest.put("version",1);
            manifest.put("source_sha256",hash);
            manifest.put("phrases",manifestPhrases);
            writeText(new File(staging,"manifest.json"),manifest.toString(2));

            // Validate before replacing current pack.
            for(int i=0;i<phrases.size();i++) {
                File f=new File(staging,String.format(Locale.ROOT,"clip-%04d.wav",i));
                if(!f.isFile()||f.length()<1500) throw new IOException("A recorded phrase was empty.");
            }

            File backup=new File(dir,"backup-"+System.nanoTime());
            backup.mkdirs();
            File[] current=dir.listFiles((d,n)->n.startsWith("clip-")||n.equals("manifest.json"));
            if(current!=null) for(File f:current) f.renameTo(new File(backup,f.getName()));
            File[] fresh=staging.listFiles();
            if(fresh!=null) for(File f:fresh) {
                if(!f.renameTo(new File(dir,f.getName()))) throw new IOException("Could not install the exact voice pack.");
            }
            deleteTree(backup);
            staging.delete();
        } catch(Exception e) {
            deleteTree(staging);
            throw e;
        } finally { source.delete(); }
        return new ImportResult(phrases.size(),hash);
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
                int frame=0;int silentStart=-1;
                while(frame<frames){
                    int wanted=Math.min(block.length,(frames-frame)*blockAlign);f.readFully(block,0,wanted);
                    int gotFrames=wanted/blockAlign;double e=0;
                    for(int i=0;i<gotFrames;i++){
                        double sum=0;
                        for(int ch=0;ch<channels;ch++){int p=i*blockAlign+ch*2;short v=(short)((block[p]&255)|((block[p+1]&255)<<8));sum+=v/32768.0;}
                        double mono=sum/channels;e+=mono*mono;
                    }
                    double rms=Math.sqrt(e/Math.max(1,gotFrames));
                    if(rms<SILENCE_RMS){if(silentStart<0)silentStart=frame;}
                    else if(silentStart>=0){if(frame-silentStart>=minSilentFrames)silence.add(new long[]{silentStart,frame});silentStart=-1;}
                    frame+=gotFrames;
                }
                if(silentStart>=0&&frames-silentStart>=minSilentFrames)silence.add(new long[]{silentStart,frames});
            }

            ArrayList<Long> boundaries=new ArrayList<>();
            for(long[] s:silence) {
                // Ignore leading/trailing silence; separators become midpoints.
                if(s[0]>rate/2 && s[1]<frames-rate/2) boundaries.add((s[0]+s[1])/2);
            }
            ArrayList<long[]> result=new ArrayList<>();
            long start=0;
            for(long b:boundaries){long[] trimmed=trim(start,b);if(trimmed[1]>trimmed[0])result.add(trimmed);start=b;}
            long[] last=trim(start,frames);if(last[1]>last[0])result.add(last);
            return result;
        }

        private long[] trim(long start,long end)throws IOException{
            long margin=rate/20; // 50 ms
            long first=end,last=start;
            int chunk=Math.max(1,rate/100);
            try(RandomAccessFile f=new RandomAccessFile(file,"r")){
                for(long at=start;at<end;at+=chunk){
                    long to=Math.min(end,at+chunk);double rms=rms(f,at,to);
                    if(rms>=SILENCE_RMS*1.5){first=at;break;}
                }
                for(long at=end;at>start;at-=chunk){
                    long from=Math.max(start,at-chunk);double rms=rms(f,from,at);
                    if(rms>=SILENCE_RMS*1.5){last=at;break;}
                }
            }
            if(first>=end||last<=start)return new long[]{0,0};
            return new long[]{Math.max(start,first-margin),Math.min(end,last+margin)};
        }

        private double rms(RandomAccessFile f,long a,long b)throws IOException{
            int frames=(int)(b-a);if(frames<=0)return 0;byte[] x=new byte[frames*blockAlign];f.seek(dataOffset+a*blockAlign);f.readFully(x);double e=0;
            for(int i=0;i<frames;i++){double sum=0;for(int ch=0;ch<channels;ch++){int p=i*blockAlign+ch*2;short v=(short)((x[p]&255)|((x[p+1]&255)<<8));sum+=v/32768.0;}double m=sum/channels;e+=m*m;}
            return Math.sqrt(e/frames);
        }

        void writeSegment(File out,long startFrame,long endFrame)throws IOException{
            int frames=(int)(endFrame-startFrame);int bytes=frames*blockAlign;
            try(RandomAccessFile in=new RandomAccessFile(file,"r");DataOutputStream d=new DataOutputStream(new BufferedOutputStream(new FileOutputStream(out)))){
                d.writeBytes("RIFF");le32(d,36+bytes);d.writeBytes("WAVEfmt ");le32(d,16);le16(d,1);le16(d,channels);le32(d,rate);le32(d,rate*blockAlign);le16(d,blockAlign);le16(d,16);d.writeBytes("data");le32(d,bytes);
                in.seek(dataOffset+startFrame*blockAlign);byte[] buf=new byte[65536];int left=bytes;
                while(left>0){int n=in.read(buf,0,Math.min(buf.length,left));if(n<0)throw new EOFException();d.write(buf,0,n);left-=n;}
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
