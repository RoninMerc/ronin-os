from pathlib import Path
import sys
root=Path(sys.argv[1]); java=root/'app/src/main/java/au/com/roningroup/patrollink'

def rep(path,old,new):
    s=path.read_text()
    if old not in s: raise RuntimeError('Patch anchor missing in '+str(path)+': '+old[:180])
    path.write_text(s.replace(old,new,1))

rep(root/'app/build.gradle','versionCode 147','versionCode 148')
rep(root/'app/build.gradle',"versionName '1.1.37'","versionName '1.1.38'")

p=java/'SpeechRules.java'
s=p.read_text()

needle='String issue=exact(o.issue,alerts);'
if needle not in s: raise RuntimeError('SpeechRules issue assignment missing')
insert='''String issue=exact(o.issue,alerts);
        String rawLocation=clean(o.property);
        String issueKey=key(issue);
        String locKey=key(rawLocation);
        // SilverTrack currently supplies Harbourfront as a location with no useful activity/subject.
        // Treat that one specific case as a normal General Patrol without altering the source record.
        boolean harbourfront=locKey.equals("harbourfront bc")||locKey.equals("harbour front bc")||locKey.equals("harbourfront");
        boolean missingIssue=issue.isEmpty()
                || issueKey.equals("subject not given")
                || issueKey.equals("subject not supplied")
                || issueKey.equals("activity description not supplied")
                || issueKey.equals("not supplied");
        if(harbourfront && missingIssue) issue="General Patrol";'''
s=s.replace(needle,insert,1)
# Ensure the location formatter uses the already-normalised Harbourfront location variable when present.
s=s.replace('String location=phrases(exact(o.property,places),pronunciations);',
            'String location=phrases(exact(rawLocation,places),pronunciations);',1)
p.write_text(s)

print('Applied v1.1.38 Harbourfront fallback: missing/subject-not-given + Harbourfront BC speaks as General Patrol. Harbourfront BC.')
