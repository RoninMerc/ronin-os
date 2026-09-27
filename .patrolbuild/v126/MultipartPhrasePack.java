package au.com.roningroup.patrollink;

import android.content.Context;
import android.net.Uri;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * Incremental exact-recording library for one voice. Each imported WAV can cover
 * one script part; parts merge into the same Evelyn phrase library.
 */
public final class MultipartPhrasePack {
    private static final double SILENCE_RMS=0.0018;
    private static final double MIN_SEPARATOR_SECONDS=1.80;
    private static final int MAX_WAV_BYTES=180*1024*1024;
    private MultipartPhrasePack(){}

    public static final class Result {
        public final int part, phrases, totalParts, totalClips;
        Result(int part,int phrases,int totalParts,int totalClips){this.part=part;this.phrases=phrases;this.totalParts=totalParts;this.totalClips=totalClips;}
    }

    private static File dir(Context c)throws IOException{
        File d=new File(c.getFilesDir(),"exact_voice_packs_v2/evelyn");
        if(!d.exists()&&!d.mkdirs())throw new IOException("Could not create Evelyn voice storage.");
        return d;
    }
    private static File manifestFile(Context c)throws IOException{return new File(dir(c),"manifest.json");}
    private static JSONObject manifest(Context c)throws Exception{
        File f=manifestFile(c);
        if(!f.isFile()){JSONObject o=new JSONObject();o.put("version",2);o.put("revision","");o.put("total_parts",0);o.put("imported_parts",new JSONArray());o.put("phrases",new JSONArray());return o;}
        return new JSONObject(read(f));
    }
    private static void save(Context c,JSONObject o)throws Exception{
        File d=dir(c),tmp=new File(d,"manifest.tmp");
        write(tmp,o.toString(2));
        File dest=new File(d,"manifest.json");
        if(dest.exists()&&!dest.delete())throw new IOException("Could not update Evelyn manifest.");
        if(!tmp.renameTo(dest))throw new IOException("Could not finalise Evelyn manifest.");
    }

    public static LinkedHashMap<String,File> clips(Context c)throws Exception{
        JSONObject o=manifest(c);JSONArray a=o.optJSONArray("phrases");LinkedHashMap<String,File> out=new LinkedHashMap<>();
        if(a==null)return out;
        File d=dir(c);
        for(int i=0;i<a.length();i++){
            JSONObject e=a.optJSONObject(i);if(e==null)continue;
            String phrase=e.optString("phrase"),file=e.optString("file");
            File f=new File(d,file);
            if(!phrase.isEmpty()&&f.isFile())out.put(ExactPhrasePack.normalize(phrase),f);
        }
        return out;
    }
    public static int clipCount(Context c){try{return clips(c).size();}catch(Exception e){return 0;}}
    public static boolean ready(Context c){return clipCount(c)>0;}
    public static String revision(Context c){try{return manifest(c).optString("revision","");}catch(Exception e){return "";}}
    public static int totalParts(Context c){try{return manifest(c).optInt("total_parts",0);}catch(Exception e){return 0;}}
    public static Set<Integer> importedParts(Context c){
        LinkedHashSet<Integer> out=new LinkedHashSet<>();
        try{JSONArray a=manifest(c).optJSONArray("imported_parts");if(a!=null)for(int i=0;i<a.length();i++)out.add(a.optInt(i));}catch(Exception ignored){}
        return out;
    }

    public static void migrateLegacy(Context c,String oldProfile)throws Exception{
        if(ready(c)||oldProfile==null||oldProfile.isEmpty()||!ExactPhrasePack.installed(c,oldProfile))return;
        LinkedHashMap<String,File> old=ExactPhrasePack.clips(c,oldProfile);
        if(old.isEmpty())return;
        File d=dir(c);JSONArray phrases=new JSONArray();
        for(Map.Entry<String,File> e:old.entrySet()){
            String key=e.getKey(),name="clip-"+sha256Text(key)+".wav";
            copyFile(e.getValue(),new File(d,name));
            JSONObject item=new JSONObject();item.put("phrase",key);item.put("file",name);phrases.put(item);
        }
        JSONObject m=new JSONObject();m.put("version",2);m.put("revision","legacy-migrated");m.put("total_parts",0);m.put("imported_parts",new JSONArray());m.put("phrases",phrases);save(c,m);
    }

    public static Result importPart(Context c,Uri uri,int part,int totalParts,String revision,List<String> phrases)throws Exception{
        if(uri==null)throw new IOException("Choose the WAV for Part "+part+".");
        if(part<1||part>totalParts||totalParts<1)throw new IOException("Invalid voice-pack part.");
        if(phrases==null||phrases.isEmpty())throw new IOException("This voice-pack part has no phrases.");

        File d=dir(c),source=new File(d,"import-part-"+part+".wav");
        try(InputStream in=c.getContentResolver().openInputStream(uri);OutputStream out=new BufferedOutputStream(new FileOutputStream(source))){
            if(in==null)throw new IOException("Could not open that WAV.");
            byte[] b=new byte[65536];int n;long total=0;
            while((n=in.read(b))!=-1){total+=n;if(total>MAX_WAV_BYTES)throw new IOException("This WAV is larger than 180 MB.");out.write(b,0,n);}
        }
        Wav wav=Wav.read(source);List<long[]> segments=wav.segments();
        if(segments.size()!=phrases.size()){
            source.delete();
            throw new IOException("Part "+part+" contains "+segments.size()+" recorded phrases, but Patrol Link expected "+phrases.size()+". Generate only the exact Part "+part+" script, including every [pause 3] between phrases.");
        }

        JSONObject m=manifest(c);
        String currentRevision=m.optString("revision","");
        if(!revision.equals(currentRevision)){
            // Keep matching recordings already present, but start part-completion tracking for this new script revision.
            m.put("revision",revision);m.put("total_parts",totalParts);m.put("imported_parts",new JSONArray());
        }else m.put("total_parts",totalParts);

        LinkedHashMap<String,JSONObject> records=new LinkedHashMap<>();
        JSONArray existing=m.optJSONArray("phrases");
        if(existing!=null)for(int i=0;i<existing.length();i++){JSONObject e=existing.optJSONObject(i);if(e!=null)records.put(ExactPhrasePack.normalize(e.optString("phrase")),e);}

        File staging=new File(d,"staging-part-"+part+"-"+System.nanoTime());
        if(!staging.mkdirs())throw new IOException("Could not prepare Part "+part+".");
        try{
            for(int i=0;i<phrases.size();i++){
                String phrase=phrases.get(i),key=ExactPhrasePack.normalize(phrase),name="clip-"+sha256Text(key)+".wav";
                File temp=new File(staging,name);long[] range=segments.get(i);wav.writeSegment(temp,range[0],range[1]);
                if(temp.length()<1500)throw new IOException("Part "+part+" contains an empty recording at phrase "+(i+1)+".");
                File dest=new File(d,name);
                if(dest.exists()&&!dest.delete())throw new IOException("Could not replace an older recording for: "+phrase);
                if(!temp.renameTo(dest))throw new IOException("Could not install recording: "+phrase);
                JSONObject e=new JSONObject();e.put("phrase",phrase);e.put("file",name);e.put("part",part);records.put(key,e);
            }
            JSONArray merged=new JSONArray();for(JSONObject e:records.values())merged.put(e);m.put("phrases",merged);
            TreeSet<Integer> imported=new TreeSet<>();JSONArray a=m.optJSONArray("imported_parts");if(a!=null)for(int i=0;i<a.length();i++)imported.add(a.optInt(i));imported.add(part);
            JSONArray done=new JSONArray();for(Integer x:imported)done.put(x);m.put("imported_parts",done);save(c,m);
        }finally{deleteTree(staging);source.delete();}
        return new Result(part,phrases.size(),totalParts,clipCount(c));
    }

    public static void clear(Context c)throws Exception{deleteTree(dir(c));}

    private static final class Wav{
        final File file;final int channels,rate,align;final long offset,bytes;
        Wav(File f,int channels,int rate,int align,long offset,long bytes){this.file=f;this.channels=channels;this.rate=rate;this.align=align;this.offset=offset;this.bytes=bytes;}
        static Wav read(File file)throws IOException{
            try(RandomAccessFile f=new RandomAccessFile(file,"r")){
                if(f.length()<44||!tag(f).equals("RIFF"))throw new IOException("Export each part as a 16-bit PCM WAV.");
                u32(f);if(!tag(f).equals("WAVE"))throw new IOException("Not a WAV recording.");
                int format=0,channels=0,rate=0,bits=0,align=0;long off=-1,size=0;
                while(f.getFilePointer()+8<=f.length()){
                    String chunk=tag(f);long n=u32(f),start=f.getFilePointer();
                    if(n<0||start+n>f.length())throw new IOException("The WAV is incomplete.");
                    if(chunk.equals("fmt ")){format=u16(f);channels=u16(f);rate=(int)u32(f);u32(f);align=u16(f);bits=u16(f);}
                    else if(chunk.equals("data")){off=start;size=n;break;}
                    f.seek(start+n+(n&1));
                }
                if(off<0||format!=1||bits!=16||channels<1||channels>2||rate<16000||rate>96000||align!=channels*2)throw new IOException("Use a 16-bit PCM WAV export (mono or stereo).");
                return new Wav(file,channels,rate,align,off,size);
            }
        }
        List<long[]> segments()throws IOException{
            int frames=(int)Math.min(Integer.MAX_VALUE,bytes/align),frame10=Math.max(1,rate/100),minSilent=(int)Math.round(MIN_SEPARATOR_SECONDS*rate);
            ArrayList<long[]> silences=new ArrayList<>();
            try(RandomAccessFile f=new RandomAccessFile(file,"r")){
                f.seek(offset);byte[] block=new byte[frame10*align];int frame=0,silentStart=-1;
                while(frame<frames){
                    int wanted=Math.min(block.length,(frames-frame)*align);f.readFully(block,0,wanted);int got=wanted/align;double e=0;
                    for(int i=0;i<got;i++){double sum=0;for(int ch=0;ch<channels;ch++){int p=i*align+ch*2;short v=(short)((block[p]&255)|((block[p+1]&255)<<8));sum+=v/32768.0;}double mono=sum/channels;e+=mono*mono;}
                    double rms=Math.sqrt(e/Math.max(1,got));
                    if(rms<SILENCE_RMS){if(silentStart<0)silentStart=frame;}else if(silentStart>=0){if(frame-silentStart>=minSilent)silences.add(new long[]{silentStart,frame});silentStart=-1;}frame+=got;
                }
                if(silentStart>=0&&frames-silentStart>=minSilent)silences.add(new long[]{silentStart,frames});
            }
            ArrayList<Long> boundaries=new ArrayList<>();for(long[] s:silences)if(s[0]>rate/2&&s[1]<frames-rate/2)boundaries.add((s[0]+s[1])/2);
            ArrayList<long[]> result=new ArrayList<>();long start=0;for(long b:boundaries){long[] t=trim(start,b);if(t[1]>t[0])result.add(t);start=b;}long[] last=trim(start,frames);if(last[1]>last[0])result.add(last);return result;
        }
        long[] trim(long start,long end)throws IOException{
            long margin=rate/20,first=end,last=start;int chunk=Math.max(1,rate/100);
            try(RandomAccessFile f=new RandomAccessFile(file,"r")){
                for(long at=start;at<end;at+=chunk){long to=Math.min(end,at+chunk);if(rms(f,at,to)>=SILENCE_RMS*1.5){first=at;break;}}
                for(long at=end;at>start;at-=chunk){long from=Math.max(start,at-chunk);if(rms(f,from,at)>=SILENCE_RMS*1.5){last=at;break;}}
            }
            if(first>=end||last<=start)return new long[]{0,0};return new long[]{Math.max(start,first-margin),Math.min(end,last+margin)};
        }
        double rms(RandomAccessFile f,long a,long b)throws IOException{
            int frames=(int)(b-a);if(frames<=0)return 0;byte[] x=new byte[frames*align];f.seek(offset+a*align);f.readFully(x);double e=0;
            for(int i=0;i<frames;i++){double sum=0;for(int ch=0;ch<channels;ch++){int p=i*align+ch*2;short v=(short)((x[p]&255)|((x[p+1]&255)<<8));sum+=v/32768.0;}double m=sum/channels;e+=m*m;}return Math.sqrt(e/frames);
        }
        void writeSegment(File out,long start,long end)throws IOException{
            int frames=(int)(end-start),data=frames*align;
            try(RandomAccessFile in=new RandomAccessFile(file,"r");DataOutputStream d=new DataOutputStream(new BufferedOutputStream(new FileOutputStream(out)))){
                d.writeBytes("RIFF");le32(d,36+data);d.writeBytes("WAVEfmt ");le32(d,16);le16(d,1);le16(d,channels);le32(d,rate);le32(d,rate*align);le16(d,align);le16(d,16);d.writeBytes("data");le32(d,data);
                in.seek(offset+start*align);byte[] buf=new byte[65536];int left=data;while(left>0){int n=in.read(buf,0,Math.min(left,buf.length));if(n<0)throw new EOFException();d.write(buf,0,n);left-=n;}
            }
        }
    }

    private static int u16(RandomAccessFile f)throws IOException{return f.readUnsignedByte()|(f.readUnsignedByte()<<8);}
    private static long u32(RandomAccessFile f)throws IOException{return (long)u16(f)|((long)u16(f)<<16);}
    private static String tag(RandomAccessFile f)throws IOException{byte[] b=new byte[4];f.readFully(b);return new String(b,StandardCharsets.US_ASCII);}
    private static void le16(DataOutputStream d,int v)throws IOException{d.writeByte(v);d.writeByte(v>>>8);}
    private static void le32(DataOutputStream d,int v)throws IOException{le16(d,v);le16(d,v>>>16);}
    private static String sha256Text(String s)throws Exception{MessageDigest md=MessageDigest.getInstance("SHA-256");byte[] h=md.digest(s.getBytes(StandardCharsets.UTF_8));StringBuilder b=new StringBuilder();for(byte x:h)b.append(String.format(Locale.ROOT,"%02x",x&255));return b.toString();}
    private static void copyFile(File a,File b)throws IOException{try(InputStream in=new FileInputStream(a);OutputStream out=new FileOutputStream(b)){byte[] x=new byte[65536];int n;while((n=in.read(x))!=-1)out.write(x,0,n);}}
    private static String read(File f)throws IOException{try(InputStream in=new FileInputStream(f)){ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] x=new byte[8192];int n;while((n=in.read(x))!=-1)b.write(x,0,n);return b.toString("UTF-8");}}
    private static void write(File f,String s)throws IOException{try(OutputStream out=new FileOutputStream(f)){out.write(s.getBytes(StandardCharsets.UTF_8));}}
    private static void deleteTree(File f){if(f==null||!f.exists())return;if(f.isDirectory()){File[] c=f.listFiles();if(c!=null)for(File x:c)deleteTree(x);}f.delete();}
}
