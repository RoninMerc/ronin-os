from pathlib import Path
import os
root=Path(os.environ.get('VANTA_PROJECT','vanta/personal'));j=root/'app/src/main/java/com/ronin/vanta'
p=j/'ForgeOutputRecovery.java';s=p.read_text();marker='  static String filePart('
assert s.count(marker)==1
s=s.replace(marker,'''  /** Discard only an invalid repair's own response variants, not validated project files. */
  static List<String> invalidRepairRecords(String prefix,int files) {
    Set<String> keys=new LinkedHashSet<>(records(prefix+"_plan_response",prefix+"_plan_correction"));
    keys.add(prefix+"_plan");
    for(int file=0;file<files;file++) {
      String id=prefix+"_file_"+file;keys.add(id);
      for(int part=0;part<4;part++) {
        keys.add(id+"_chunk_"+part);
        String phase=id+"_part_"+part;
        keys.add(phase);keys.add(phase+"_output_limit");
        keys.add(phase+"_expanded_1");keys.add(phase+"_expanded_1_output_limit");
      }
    }
    return new ArrayList<>(keys);
  }

'''+marker);p.write_text(s)
p=j/'ForgeAuthor.java';s=p.read_text();start=s.index('        List<String> discard =',s.index('ForgeRepairGuard.check('));end=s.index('        throw new ForgeRecovery.InvalidOutput(',start)
s=s[:start]+'''        List<String> discard=ForgeOutputRecovery.invalidRepairRecords(prefix,proposed.length());
'''+s[end:];p.write_text(s)
p=root/'app/src/test/java/com/ronin/vanta/OutputRecoveryTest.java';s=p.read_text().rstrip();assert s.endswith('}')
s=s[:-1]+'''
 @Test public void invalidRepairCannotReuseTruncatedOrExpandedRejectedSource()throws Exception {
   JSONObject before=Pipeline090Test.project();String path="app/src/test/java/com/ronin/forge/verification/GuardTest.java";
   before.getJSONArray("files").put(Pipeline090Test.file(path,"import org.junit.Test; public class GuardTest { @Test public void preservesVerification() {} }"));
   try {
     ForgeAuthor.author("repair","android","Preserve tests",before,"error: fix implementation",60000,
       (id,system,user,max)->system.contains("Do not include file content")
         ?new JSONObject().put("name","Repair").put("files",new JSONArray().put(new JSONObject().put("path",path).put("purpose","Repair the failing test without disabling it"))).toString()
         :"import org.junit.Test; import org.junit.Ignore; public class GuardTest { @Ignore @Test public void preservesVerification() {} }",
       new Docs(),(file,done,total)->{});
     fail("Disabling an existing test must fail the repair guard");
   }catch(ForgeRecovery.InvalidOutput invalid) {
     assertTrue(invalid.discard.contains("repair_plan_response_compact_2_output_limit"));
     assertTrue(invalid.discard.contains("repair_plan_correction_compact_1"));
     assertTrue(invalid.discard.contains("repair_file_0_part_0_expanded_1"));
     assertTrue(invalid.discard.contains("repair_file_0_part_3_expanded_1_output_limit"));
     assertFalse(invalid.discard.contains("author_ready"));
     assertFalse(invalid.discard.contains("project"));
   }
 }
 @Test public void repairCheckpointDiscardKeysAreUniqueAndBounded(){
   List<String> keys=ForgeOutputRecovery.invalidRepairRecords("repair_files_4",2);
   assertEquals(keys.size(),new HashSet<>(keys).size());assertTrue(keys.size()<80);
   assertTrue(keys.contains("repair_files_4_file_1_chunk_3"));assertFalse(keys.contains("repair_files_4_file_2"));
 }
}
''';p.write_text(s)
print('Invalid repair handovers discard all of their own compact/expanded response variants while retaining the valid project.')
