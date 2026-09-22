package au.com.roningroup.patrollink;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Lossless PCM16 WAV handling. No model, gain change, resampling or guessed phrase positions. */
public final class ExactWav {
    public final File file;
    public final int rate,channels,align;
    public final long data,bytes,frames;
    private ExactWav(File f,int r,int c,int a,long d,long n){file=f;rate=r;channels=c;align=a;data=d;bytes=n;frames=n/a;}
    static int u16(RandomAccessFile f)throws IOException{return f.readUnsignedByte()|(f.readUnsignedByte()<<8);}
    static long u32(RandomAccessFile f)throws IOException{return (long)u16(f)|((long)u16(f)<<16);}
    static String tag(RandomAccessFile f)throws IOException{byte[] b=new byte[4];f.readFully(b);return new String(b,StandardCharsets.US_ASCII);}
    public static ExactWav open(File file)throws IOException{
        try(RandomAccessFile f=new RandomAccessFile(file,"r")){
            if(f.length()<44||!"RIFF".equals(tag(f)))throw new IOException("Choose an uncompressed 16-bit PCM WAV export.");
            long size=u32(f);if(!"WAVE".equals(tag(f))||size+8>f.length())throw new IOException("Incomplete WAV file.");
            int format=0,channels=0,rate=0,bits=0,align=0;long data=-1,bytes=0;
            while(f.getFilePointer()+8<=f.length()){
                String t=tag(f);long n=u32(f),p=f.getFilePointer();if(p+n>f.length())throw new IOException("Incomplete WAV chunk.");
                if("fmt ".equals(t)){
                    if(n<16)throw new IOException("Invalid WAV header.");format=u16(f);channels=u16(f);rate=(int)u32(f);u32(f);align=u16(f);bits=u16(f);
                    if(format==65534&&n>=40){f.seek(p+24);format=u16(f);}
                }else if("data".equals(t)&&data<0){data=p;bytes=n;}
                f.seek(p+n+(n&1));
            }
            if(format!=1||bits!=16||channels<1||channels>2||rate<8000||rate>192000||align!=channels*2||data<0||bytes<align||bytes%align!=0)throw new IOException("Use a mono or stereo 16-bit PCM WAV. The app will not convert or recreate the voice.");
            return new ExactWav(file,rate,channels,align,data,bytes);
        }
    }
    public double seconds(){return frames/(double)rate;}
    public void cut(File output,double from,double to)throws IOException{
        if(!Double.isFinite(from)||!Double.isFinite(to)||from<0||to<=from||to>seconds()+.0001||to-from>180)throw new IOException("Choose a valid phrase range of no more than three minutes.");
        long first=Math.max(0,Math.round(from*rate)),last=Math.min(frames,Math.round(to*rate));long length=(last-first)*align;
        if(length<align)throw new IOException("The selected phrase is empty.");
        try(RandomAccessFile in=new RandomAccessFile(file,"r");DataOutputStream out=new DataOutputStream(new BufferedOutputStream(new FileOutputStream(output)))){
            out.writeBytes("RIFF");le32(out,36+length);out.writeBytes("WAVEfmt ");le32(out,16);le16(out,1);le16(out,channels);le32(out,rate);le32(out,(long)rate*align);le16(out,align);le16(out,16);out.writeBytes("data");le32(out,length);
            in.seek(data+first*align);byte[] b=new byte[65536];long remaining=length;
            while(remaining>0){int n=in.read(b,0,(int)Math.min(b.length,remaining));if(n<0)throw new EOFException("Audio ended early.");out.write(b,0,n);remaining-=n;}
        }
    }
    /** Suggested boundaries from actual silence; labels are NEVER assigned without user review. */
    public List<double[]> suggestRanges(double silenceSeconds)throws IOException{
        List<double[]> result=new ArrayList<>();int hop=Math.max(1,rate/100);long quiet=0,start=-1,at=0,lastSound=0;
        try(RandomAccessFile in=new RandomAccessFile(file,"r")){
            in.seek(data);byte[] block=new byte[hop*align];
            while(at<frames){int count=(int)Math.min(hop,frames-at);in.readFully(block,0,count*align);long energy=0;
                for(int i=0;i<count*channels;i++){int p=i*2;int v=(short)((block[p]&255)|(block[p+1]<<8));energy+=(long)v*v;}
                boolean sound=Math.sqrt(energy/(double)(count*channels))>250;
                if(sound){if(start<0)start=Math.max(0,at-rate/10);quiet=0;lastSound=at+count;}
                else if(start>=0){quiet+=count;if(quiet>=rate*silenceSeconds){result.add(new double[]{start/(double)rate,Math.min(frames,lastSound+rate/10)/(double)rate});start=-1;quiet=0;}}
                at+=count;
            }
            if(start>=0)result.add(new double[]{start/(double)rate,Math.min(frames,lastSound+rate/10)/(double)rate});
        }
        return result;
    }
    static void le16(DataOutputStream out,long n)throws IOException{out.writeByte((int)n);out.writeByte((int)(n>>>8));}
    static void le32(DataOutputStream out,long n)throws IOException{le16(out,n);le16(out,n>>>16);}
}
