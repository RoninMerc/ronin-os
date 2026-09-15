from pathlib import Path
import os
root=Path(os.environ.get('VANTA_PROJECT','vanta/personal'))
p=root/'app/src/main/java/com/ronin/vanta/ChatHandover.java'
s=p.read_text()
s=s.replace('''/**\n * Bounded same-provider recovery for explicit model-availability failures, never safety refusals.\n */''','''/**\n * Bounded compatible-provider recovery for explicit technical model-availability failures.\n * Policy/safety refusals and ambiguous paid requests are never replayed through this path.\n */''')
old='''    for (int i = 0; attempts != null && i < attempts.length(); i++)\n      tried.add(attempts.optString(i));\n    tried.add(input.getJSONObject("model").getString("id"));'''
new='''    for (int i = 0; attempts != null && i < attempts.length(); i++)\n      tried.add(attempts.optString(i));\n    String activeProvider = input.getJSONObject("provider").optString("id", provider.id);\n    String activeModel = input.getJSONObject("model").getString("id");\n    // Accept old model-only checkpoint entries, but write provider-qualified attempts from 0.12 onward.\n    tried.add(activeProvider + "/" + activeModel);'''
assert old in s
s=s.replace(old,new,1)
old='''      String id = choice.getJSONObject("model").getString("id");\n      ModelInfo model = e.registry.find(provider.id, id);\n      if (tried.contains(id)\n          || !TaskModels.eligible(p, model, task, inputs, low, System.currentTimeMillis()))'''
new='''      String id = choice.getJSONObject("model").getString("id");\n      ModelInfo model = e.registry.find(p.id, id);\n      String attemptKey = p.id + "/" + id;\n      if (model == null\n          || tried.contains(id)\n          || tried.contains(attemptKey)\n          || !TaskModels.eligible(p, model, task, inputs, low, System.currentTimeMillis()))'''
assert old in s
s=s.replace(old,new,1)
p.write_text(s)
print('Reviewed cross-provider chat handover: destination registry lookup and provider-qualified attempt tracking corrected.')
