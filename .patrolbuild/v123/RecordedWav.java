package au.com.roningroup.patrollink;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Splits PCM WAV at reviewed pauses. Audio samples are copied, never synthesised or normalised. */
public final class RecordedWav {
    private RecordedWav() {}
    public static final class Format {
        public final int rate, channels, align; public final long offset, frames;
        Format(int r,int c,int a,long o,long n){rate=r;channels=c;align=a;offset=o;frames=n;}
        public double seconds(){return frames/(double)rate;}
    }
    public static final class Span {
        public final long start,end;
        Span(long a,long b){start=a;end=b;}
    }
    private static int u16(RandomAccessFile f)throws IOException{return f.readUnsignedByte()|(f.readUnsignedByte()<<8);}
    private static long u32(RandomAccessFile f)throws IOException{return (long)u16(f)|((long)u16(f)<<16);}
    private static String tag(RandomAccessFile f)throws IOException{byte[] b=new byte[4];f.readFully(b);return new String(b,StandardCharsets.US_ASCII);}
    public static Format inspect(File file)throws IOException{
        try(RandomAccessFile f=new RandomAccessFile(file,"r")){
            if(f.length()<44||!tag(f).equals("RIFF"))throw new IOException("Export a 16-bit PCM WAV from the voice website.");
            u32(f);if(!tag(f).equals("WAVE"))throw new IOException("This is not a WAV recording.");
            int encoding=0,channels=0,rate=0,bits=0,align=0;long offset=-1,size=0;
            while(f.getFilePointer()+8<=f.length()){
                String name=tag(f);long n=u32(f),start=f.getFilePointer();
                if(start+n>f.length())throw new IOException("Incomplete WAV file.");
                if(name.equals("fmt ")){if(n<16)throw new IOException("Invalid WAV header.");encoding=u16(f);channels=u16(f);rate=(int)u32(f);u32(f);align=u16(f);bits=u16(f);
                    if(encoding==65534&&n>=40){f.seek(start+24);encoding=u16(f);}
                }else if(name.equals("data")){offset=start;size=n;if(rate>0)break;}
                f.seek(start+n+(n&1));
            }
            if(encoding!=1||bits!=16||channels<1||channels>2||rate<8000||rate>192000||align!=channels*2||offset<0||size%align!=0)
                throw new IOException("Use 16-bit PCM WAV, mono or stereo. No audio conversion is performed during import.");
            long frames=size/align;if(frames<rate/10||frames>(long)rate*1800)throw new IOException("Use a recording between 0.1 seconds and 30 minutes.");
            return new Format(rate,channels,align,offset,frames);
        }
    }
    public static List<String> scriptLines(String script)throws IOException{
        ArrayList<String> out=new ArrayList<>();Set<String> seen=new HashSet<>();
        for(String raw:(script==null?"":script).split("\\R")){
            String line=raw.trim();if(line.isEmpty())continue;
            if(line.length()>8000)throw new IOException("One recording line is too long.");
            if(!seen.add(key(line)))throw new IOException("The script repeats a line. Record each distinct announcement once.");out.add(line);
        }
        if(out.isEmpty()||out.size()>250)throw new IOException("Paste the exact recording script: 1 to 250 nonblank lines per batch.");return out;
    }
    /** Ignore case, repeated whitespace and terminal sentence punctuation only. */
    public static String key(String s){return (s==null?"":s).replaceAll("[\\s\\p{Z}]+"," ").trim().replaceAll("[.!?]+$","").trim().toLowerCase(Locale.ROOT);}
    public static List<Span> split(File source,int pauseMs)throws IOException{
        if(pauseMs<150||pauseMs>3000)throw new IOException("Pause threshold must be 150 to 3,000 milliseconds.");
        Format fmt=inspect(source);long gap=(long)fmt.rate*pauseMs/1000,pad=fmt.rate/12;int hop=Math.max(1,fmt.rate/100);
        List<Span> result=new ArrayList<>();long started=-1,lastSound=0;
        try(RandomAccessFile f=new RandomAccessFile(source,"r")){
            f.seek(fmt.offset);byte[] block=new byte[hop*fmt.align];
            for(long at=0;at<fmt.frames;){int frames=(int)Math.min(hop,fmt.frames-at);f.readFully(block,0,frames*fmt.align);int peak=0;
                for(int b=0;b<frames*fmt.align;b+=2){int v=(short)((block[b]&255)|((block[b+1]&255)<<8));peak=Math.max(peak,Math.abs(v));}
                if(peak>80){if(started<0)started=Math.max(0,at-pad);lastSound=at+frames;}
                else if(started>=0&&at+frames-lastSound>=gap){if(lastSound-started>fmt.rate/10)result.add(new Span(started,Math.min(fmt.frames,lastSound+pad)));started=-1;}
                at+=frames;
            }
            if(started>=0&&lastSound-started>fmt.rate/10)result.add(new Span(started,Math.min(fmt.frames,lastSound+pad)));
        }
        return result;
    }
    public static void slice(File source,File output,Span span)throws IOException{
        Format fmt=inspect(source);if(span.start<0||span.end>fmt.frames||span.end<=span.start)throw new IOException("Invalid recording boundary.");
        long bytes=(span.end-span.start)*fmt.align;if(bytes>Integer.MAX_VALUE)throw new IOException("Recording is too large.");
        try(RandomAccessFile in=new RandomAccessFile(source,"r");DataOutputStream out=new DataOutputStream(new BufferedOutputStream(new FileOutputStream(output)))){
            out.writeBytes("RIFF");le32(out,(int)bytes+36);out.writeBytes("WAVEfmt ");le32(out,16);le16(out,1);le16(out,fmt.channels);
            le32(out,fmt.rate);le32(out,fmt.rate*fmt.align);le16(out,fmt.align);le16(out,16);out.writeBytes("data");le32(out,(int)bytes);
            in.seek(fmt.offset+span.start*fmt.align);byte[] b=new byte[65536];while(bytes>0){int n=(int)Math.min(bytes,b.length);in.readFully(b,0,n);out.write(b,0,n);bytes-=n;}
        }
    }
    private static void le16(DataOutputStream d,int n)throws IOException{d.writeByte(n);d.writeByte(n>>>8);}
    private static void le32(DataOutputStream d,int n)throws IOException{le16(d,n);le16(d,n>>>16);}
}
