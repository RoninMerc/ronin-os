package au.com.roningroup.patrollink;

import java.io.*;
import java.nio.*;
import java.security.MessageDigest;
import java.util.*;

/** Local PCM WAV import. No transcript alignment, network, or prerecorded phrase offsets. */
public final class VoiceAudio {
    public static final int RATE=24000;
    private VoiceAudio() {}
    private static int u16(RandomAccessFile r)throws IOException {return r.readUnsignedByte()|(r.readUnsignedByte()<<8);}
    private static long u32(RandomAccessFile r)throws IOException {return (long)u16(r)|((long)u16(r)<<16);}
    private static String four(RandomAccessFile r)throws IOException {byte[] b=new byte[4];r.readFully(b);return new String(b,"US-ASCII");}
    public static float[] reference(File file)throws IOException {
        try(RandomAccessFile r=new RandomAccessFile(file,"r")) {
            if(r.length()<44||!four(r).equals("RIFF"))throw new IOException("Export the reference as an uncompressed WAV file.");
            u32(r);if(!four(r).equals("WAVE"))throw new IOException("Not a WAV recording.");
            int format=0,channels=0,rate=0,bits=0,block=0; long dataAt=-1,dataBytes=0;
            while(r.getFilePointer()+8<=r.length()) {
                String chunk=four(r);long n=u32(r),start=r.getFilePointer();
                if(n<0||start+n>r.length())throw new IOException("The WAV file is incomplete.");
                if(chunk.equals("fmt ")) {
                    if(n<16)throw new IOException("Invalid WAV format.");
                    format=u16(r);channels=u16(r);rate=(int)u32(r);u32(r);block=u16(r);bits=u16(r);
                    if(format==65534&&n>=40){r.seek(start+24);format=u16(r);}
                } else if(chunk.equals("data")){dataAt=start;dataBytes=n; if(format!=0)break;}
                r.seek(Math.min(r.length(),start+n+(n&1)));
            }
            if(dataAt<0||channels<1||channels>2||rate<8000||rate>192000||!(format==1||format==3)||!(bits==16||bits==24||bits==32)||block!=channels*bits/8)
                throw new IOException("Use mono or stereo PCM WAV, preferably 16-bit, at 24 or 48 kHz.");
            if(format==3&&bits!=32)throw new IOException("Unsupported floating point WAV.");
            int frames=(int)Math.min(dataBytes/block,rate*20L);
            if(frames<rate*3)throw new IOException("Use at least three seconds of clear speech.");
            byte[] pcm=new byte[frames*block];r.seek(dataAt);r.readFully(pcm);
            float[] mono=new float[frames];int bytes=bits/8;
            for(int i=0;i<frames;i++){
                double value=0;
                for(int ch=0;ch<channels;ch++){
                    int at=i*block+ch*bytes;int v=(pcm[at]&255)|((pcm[at+1]&255)<<8);
                    float sample;
                    if(bits==16)sample=(short)v/32768f;
                    else if(bits==24){v|=(pcm[at+2]&255)<<16;if((v&0x800000)!=0)v|=0xff000000;sample=v/8388608f;}
                    else{v|=(pcm[at+2]&255)<<16;v|=pcm[at+3]<<24;sample=format==3?Float.intBitsToFloat(v):v/2147483648f;}
                    if(!Float.isFinite(sample))sample=0;value+=Math.max(-1,Math.min(1,sample));
                }
                mono[i]=(float)(value/channels);
            }
            int begin=0,end=frames;
            while(begin<end&&Math.abs(mono[begin])<0.007f)begin++;
            begin=Math.max(0,begin-rate/15);end=Math.min(end,begin+rate*12);
            while(end>begin&&Math.abs(mono[end-1])<0.003f)end--;
            if(end-begin<rate*2)throw new IOException("The reference contains too little audible speech.");
            int count=(int)((long)(end-begin)*RATE/rate);float[] out=new float[count];double sum=0;
            for(int i=0;i<count;i++){
                double x=begin+(double)i*rate/RATE;int j=(int)x;double f=x-j;
                out[i]=(float)(mono[j]*(1-f)+mono[Math.min(j+1,end-1)]*f);sum+=out[i]*out[i];
            }
            double rms=Math.sqrt(sum/count);if(rms<0.004)throw new IOException("The reference is silent or too quiet.");
            float gain=(float)Math.min(4.0,0.1/rms);
            for(int i=0;i<out.length;i++)out[i]=Math.max(-0.98f,Math.min(0.98f,out[i]*gain));
            return out;
        }
    }
    public static void write(File f,float[] pcm,int rate)throws IOException {
        File parent=f.getParentFile();if(parent!=null&&!parent.exists()&&!parent.mkdirs())throw new IOException("Cannot create audio folder.");
        try(OutputStream out=new BufferedOutputStream(new FileOutputStream(f))){
            ByteBuffer h=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
            h.put("RIFF".getBytes("US-ASCII")).putInt(36+pcm.length*2).put("WAVEfmt ".getBytes("US-ASCII"));
            h.putInt(16).putShort((short)1).putShort((short)1).putInt(rate).putInt(rate*2).putShort((short)2).putShort((short)16).put("data".getBytes("US-ASCII")).putInt(pcm.length*2);out.write(h.array());
            byte[] buffer=new byte[8192];int n=0;
            for(float sample:pcm){int v=(int)(Math.max(-1,Math.min(1,Float.isFinite(sample)?sample:0))*32767);buffer[n++]=(byte)v;buffer[n++]=(byte)(v>>8);if(n==buffer.length){out.write(buffer);n=0;}}
            if(n>0)out.write(buffer,0,n);
        }
    }
    public static String hash(File f)throws Exception {
        MessageDigest md=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(f)){byte[] b=new byte[65536];int n;while((n=in.read(b))>0)md.update(b,0,n);}return hex(md.digest());
    }
    public static String hash(String s)throws Exception{return hex(MessageDigest.getInstance("SHA-256").digest(s.getBytes("UTF-8")));}
    private static String hex(byte[] b){StringBuilder s=new StringBuilder();for(byte x:b)s.append(String.format(Locale.ROOT,"%02x",x&255));return s.toString();}
}
