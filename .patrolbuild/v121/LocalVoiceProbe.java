import com.k2fsa.sherpa.onnx.*;
import java.nio.file.*;
public class LocalVoiceProbe {
    public static void main(String[] args)throws Exception{
        String d=args[0];
        OfflineTtsPocketModelConfig p=OfflineTtsPocketModelConfig.builder().setLmFlow(d+"/lm_flow.int8.onnx").setLmMain(d+"/lm_main.int8.onnx").setEncoder(d+"/encoder.onnx").setDecoder(d+"/decoder.int8.onnx").setTextConditioner(d+"/text_conditioner.onnx").setVocabJson(d+"/vocab.json").setTokenScoresJson(d+"/token_scores.json").build();
        OfflineTtsModelConfig m=OfflineTtsModelConfig.builder().setPocket(p).setNumThreads(2).setDebug(false).build();
        OfflineTts model=new OfflineTts(OfflineTtsConfig.builder().setModel(m).build());
        WaveReader reference=new WaveReader(args[1]);GenerationConfig g=new GenerationConfig();g.setReferenceAudio(reference.getSamples());g.setReferenceSampleRate(reference.getSampleRate());g.setNumSteps(5);g.setSilenceScale(.15f);g.setSpeed(1f);
        String text=Files.readString(Path.of(args[2]));long start=System.nanoTime();GeneratedAudio audio=model.generateWithConfig(text,g);
        audio.save(args[3]);System.out.println("text="+text.trim());System.out.println("sampleRate="+audio.getSampleRate());System.out.println("duration="+audio.getSamples().length/(double)audio.getSampleRate());System.out.println("generationSeconds="+(System.nanoTime()-start)/1e9);model.release();
    }
}
