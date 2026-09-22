package au.com.roningroup.patrollink;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Bounded WAV decoding and local reference preparation; no network or speech service. */
public final class VoiceReference {
    public static final int RATE = 24000;
    private VoiceReference() {}
    private static int u16(RandomAccessFile f) throws IOException { return f.readUnsignedByte() | (f.readUnsignedByte() << 8); }
    private static long u32(RandomAccessFile f) throws IOException { return (long)u16(f) | ((long)u16(f) << 16); }
    private static String tag(RandomAccessFile f) throws IOException { byte[] b = new byte[4]; f.readFully(b); return new String(b, StandardCharsets.US_ASCII); }
    public static float[] read(File source) throws IOException {
        try (RandomAccessFile f = new RandomAccessFile(source, "r")) {
            if (f.length() < 44 || !tag(f).equals("RIFF")) throw new IOException("Export the voice as a PCM WAV file, then import it.");
            u32(f); if (!tag(f).equals("WAVE")) throw new IOException("Not a WAV recording.");
            int format = 0, channels = 0, rate = 0, bits = 0, align = 0;
            long data = -1, size = 0;
            while (f.getFilePointer() + 8 <= f.length()) {
                String chunk = tag(f); long n = u32(f), start = f.getFilePointer();
                if (n < 0 || start + n > f.length()) throw new IOException("Incomplete WAV file.");
                if (chunk.equals("fmt ")) {
                    if (n < 16) throw new IOException("Invalid WAV header.");
                    format = u16(f); channels = u16(f); rate = (int)u32(f); u32(f); align = u16(f); bits = u16(f);
                    if (format == 65534 && n >= 40) { f.seek(start + 24); format = u16(f); }
                } else if (chunk.equals("data")) { data = start; size = n; if (rate > 0) break; }
                f.seek(start + n + (n & 1));
            }
            if (data < 0 || rate < 8000 || rate > 192000 || channels < 1 || channels > 2 || align != channels * (bits / 8)
                    || !((format == 1 && (bits == 8 || bits == 16 || bits == 24 || bits == 32)) || (format == 3 && bits == 32)))
                throw new IOException("Use a mono or stereo PCM WAV export (16-bit is recommended).");
            int count = (int)Math.min(size / align, (long)rate * 25);
            if (count < rate * 3) throw new IOException("Use at least three seconds of clear speech.");
            float[] input = new float[count]; f.seek(data);
            byte[] frame = new byte[align];
            for (int i = 0; i < count; i++) {
                f.readFully(frame); float sum = 0;
                for (int ch = 0; ch < channels; ch++) {
                    int p = ch * bits / 8; float v;
                    if (bits == 8) v = ((frame[p] & 255) - 128) / 128f;
                    else if (bits == 16) v = (short)((frame[p] & 255) | ((frame[p + 1] & 255) << 8)) / 32768f;
                    else if (bits == 24) { int q = (frame[p] & 255) | ((frame[p + 1] & 255) << 8) | (frame[p + 2] << 16); v = q / 8388608f; }
                    else { int q = (frame[p] & 255) | ((frame[p + 1] & 255) << 8) | ((frame[p + 2] & 255) << 16) | (frame[p + 3] << 24); v = format == 3 ? Float.intBitsToFloat(q) : q / 2147483648f; }
                    if (!Float.isFinite(v)) throw new IOException("The WAV contains invalid samples.");
                    sum += Math.max(-1, Math.min(1, v));
                }
                input[i] = sum / channels;
            }
            if (rate == RATE) return input;
            int n = (int)((long)input.length * RATE / rate); float[] output = new float[n];
            for (int i = 0; i < n; i++) { double at = i * (double)rate / RATE; int lo = (int)at, hi = Math.min(lo + 1, input.length - 1); output[i] = (float)(input[lo] + (input[hi] - input[lo]) * (at - lo)); }
            return output;
        }
    }
    public static void prepare(File source, File destination) throws IOException {
        float[] input = read(source); int hop = RATE / 100, start = 0;
        while (start + hop < input.length && rms(input, start, start + hop) < .004) start += hop;
        start = Math.max(0, start - RATE / 10);
        int maxEnd = Math.min(input.length, start + RATE * 12), end = maxEnd;
        // Prefer a natural pause rather than cutting through a word. Never infer phrase offsets.
        int quiet = 0, lastPause = -1;
        for (int at = start; at + hop <= maxEnd; at += hop) {
            if (rms(input, at, at + hop) < .004) quiet += hop; else quiet = 0;
            if (quiet >= RATE / 5 && at > start + RATE * 5) lastPause = at - quiet + RATE / 10;
        }
        if (lastPause > start + RATE * 5) end = lastPause;
        if (end - start < RATE * 3 || rms(input, start, end) < .005) throw new IOException("No usable clear speech was found near the start of this WAV.");
        float[] out = Arrays.copyOfRange(input, start, end);
        float gain = (float)Math.min(3, .09 / Math.max(.001, rms(out, 0, out.length)));
        for (int i = 0; i < out.length; i++) out[i] = Math.max(-.98f, Math.min(.98f, out[i] * gain));
        write(destination, out);
    }
    private static double rms(float[] x, int a, int b) { double e = 0; for (int i = a; i < b; i++) e += x[i] * x[i]; return Math.sqrt(e / Math.max(1, b - a)); }
    public static void write(File file, float[] samples) throws IOException {
        try (DataOutputStream d = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(file)))) {
            d.writeBytes("RIFF"); le32(d, 36 + samples.length * 2); d.writeBytes("WAVEfmt "); le32(d, 16); le16(d, 1); le16(d, 1);
            le32(d, RATE); le32(d, RATE * 2); le16(d, 2); le16(d, 16); d.writeBytes("data"); le32(d, samples.length * 2);
            for (float v : samples) le16(d, (int)(Math.max(-1, Math.min(1, v)) * 32767));
        }
    }
    private static void le16(DataOutputStream d, int v) throws IOException { d.writeByte(v); d.writeByte(v >>> 8); }
    private static void le32(DataOutputStream d, int v) throws IOException { le16(d, v); le16(d, v >>> 16); }
    public static String hash(File file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) { byte[] b = new byte[65536]; int n; while ((n = in.read(b)) != -1) md.update(b, 0, n); }
        StringBuilder s = new StringBuilder(); for (byte b : md.digest()) s.append(String.format(Locale.ROOT, "%02x", b & 255)); return s.toString();
    }
}
