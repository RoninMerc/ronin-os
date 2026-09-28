package au.com.roningroup.patrollink;

import java.io.*;
import java.util.*;

/**
 * Resolves exact recorded speech using the fewest available clips.
 *
 * Direct full-sentence recordings win. If a full sentence is absent, the
 * resolver can compose it from smaller exact clips such as:
 *   "General Patrol" + "Bobsled Lane"
 *
 * It never synthesises missing words.
 */
public final class ExactPhraseResolver {
    private ExactPhraseResolver(){}

    public static List<File> resolve(LinkedHashMap<String,File> map,String text)throws IOException{
        String clean=SpeechRules.clean(text);
        if(clean.isEmpty())return Collections.emptyList();

        File direct=map.get(ExactPhrasePack.normalize(clean));
        if(direct!=null)return Collections.singletonList(direct);

        ArrayList<File> result=new ArrayList<>();
        for(String raw:clean.split("(?<=[.!?])\\s+")){
            String part=raw.replaceAll("[.!?]+$","").trim();
            if(part.isEmpty())continue;

            File exact=map.get(ExactPhrasePack.normalize(part));
            if(exact!=null){result.add(exact);continue;}

            List<File> composed=compose(map,part);
            if(composed==null)
                throw new IOException("No exact recording or recorded phrase combination for: \""+part+"\".");
            result.addAll(composed);
        }
        if(result.isEmpty())throw new IOException("No exact recording matches this update.");
        return result;
    }

    static List<File> compose(LinkedHashMap<String,File> map,String text){
        String target=words(text);
        if(target.isEmpty())return Collections.emptyList();
        String[] wanted=target.split(" ");

        class Candidate {
            final String[] words;
            final File file;
            Candidate(String[] words,File file){this.words=words;this.file=file;}
        }

        ArrayList<Candidate> candidates=new ArrayList<>();
        for(Map.Entry<String,File> e:map.entrySet()){
            String key=words(e.getKey());
            if(key.isEmpty())continue;
            candidates.add(new Candidate(key.split(" "),e.getValue()));
        }
        candidates.sort((a,b)->Integer.compare(b.words.length,a.words.length));

        @SuppressWarnings("unchecked")
        List<File>[] best=new List[wanted.length+1];
        best[0]=new ArrayList<>();

        for(int at=0;at<wanted.length;at++){
            if(best[at]==null)continue;
            for(Candidate c:candidates){
                if(at+c.words.length>wanted.length)continue;
                boolean match=true;
                for(int j=0;j<c.words.length;j++)
                    if(!wanted[at+j].equals(c.words[j])){match=false;break;}
                if(!match)continue;

                int end=at+c.words.length;
                ArrayList<File> path=new ArrayList<>(best[at]);
                path.add(c.file);
                if(best[end]==null || path.size()<best[end].size())best[end]=path;
            }
        }
        return best[wanted.length];
    }

    private static String words(String raw){
        if(raw==null)return "";
        return raw.toLowerCase(Locale.ROOT)
                .replace('–',' ').replace('—',' ').replace('-',' ')
                .replaceAll("[^a-z0-9]+"," ")
                .replaceAll("\\s+"," ").trim();
    }
}
