package au.com.roningroup.patrollink;

import org.junit.Test;
import java.io.File;
import java.util.*;
import static org.junit.Assert.*;

public class ExactPhraseResolverTest {
    @Test public void exactFullPhraseWinsOverComposition()throws Exception{
        LinkedHashMap<String,File> map=new LinkedHashMap<>();
        File full=new File("full.wav"),general=new File("general.wav"),bobsled=new File("bobsled.wav");
        map.put(ExactPhrasePack.normalize("General Patrol"),general);
        map.put(ExactPhrasePack.normalize("Bobsled Lane"),bobsled);
        map.put(ExactPhrasePack.normalize("General Patrol - Bobsled Lane"),full);

        List<File> out=ExactPhraseResolver.resolve(map,"General Patrol - Bobsled Lane");
        assertEquals(1,out.size());
        assertEquals(full,out.get(0));
    }

    @Test public void canComposeNewStreetFromExistingFunctionAndAddedPlace()throws Exception{
        LinkedHashMap<String,File> map=new LinkedHashMap<>();
        File general=new File("general.wav"),bobsled=new File("bobsled.wav");
        map.put(ExactPhrasePack.normalize("General Patrol"),general);
        map.put(ExactPhrasePack.normalize("Bobsled Lane"),bobsled);

        List<File> out=ExactPhraseResolver.resolve(map,"General Patrol - Bobsled Lane");
        assertEquals(Arrays.asList(general,bobsled),out);
    }

    @Test public void longestAvailablePiecesMinimiseClipCount()throws Exception{
        LinkedHashMap<String,File> map=new LinkedHashMap<>();
        File guard=new File("guard.wav"),patrol=new File("patrol.wav"),guardPatrol=new File("guard-patrol.wav"),tradition=new File("tradition.wav");
        map.put(ExactPhrasePack.normalize("Guard"),guard);
        map.put(ExactPhrasePack.normalize("Patrol"),patrol);
        map.put(ExactPhrasePack.normalize("Guard Patrol"),guardPatrol);
        map.put(ExactPhrasePack.normalize("Tradition Place"),tradition);

        List<File> out=ExactPhraseResolver.resolve(map,"Guard Patrol - Tradition Place");
        assertEquals(2,out.size());
        assertEquals(guardPatrol,out.get(0));
        assertEquals(tradition,out.get(1));
    }

    @Test public void refusesToInventMissingWords(){
        LinkedHashMap<String,File> map=new LinkedHashMap<>();
        map.put(ExactPhrasePack.normalize("General Patrol"),new File("general.wav"));
        try{
            ExactPhraseResolver.resolve(map,"General Patrol - Unknown Lane");
            fail("Resolver invented missing speech");
        }catch(Exception expected){
            assertTrue(expected.getMessage().contains("Unknown Lane") || expected.getMessage().contains("General Patrol"));
        }
    }
}
