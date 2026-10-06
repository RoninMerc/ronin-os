from pathlib import Path

ROOT = Path(__file__).resolve().parent
main_path = ROOT / "app/src/main/java/com/ronin/vanta/MainActivity.java"
gradle_path = ROOT / "app/build.gradle"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)


# Version bump.
gradle = gradle_path.read_text()
gradle = replace_once(gradle, "versionCode 1", "versionCode 2", "versionCode")
gradle = replace_once(gradle, "versionName '0.1.0'", "versionName '0.2.0'", "versionName")
gradle_path.write_text(gradle)

main = main_path.read_text()

main = replace_once(
    main,
    'String[][] items = {{"CHAT","chat"},{"IMAGE","image"},{"VIDEO","video"},{"CONVERSATE","converse"},{"SETUP","setup"}};',
    'String[][] items = {{"TEXT","chat"},{"CODE","code"},{"IMAGE","image"},{"VIDEO","video"},{"CONVERSATE","converse"},{"SETUP","setup"}};',
    "navigation"
)

main = replace_once(
    main,
    'if ("chat".equals(mode)) buildChat(false);',
    'if ("chat".equals(mode) || "code".equals(mode)) buildChat(false);',
    "code mode route"
)

main = replace_once(
    main,
    '    private void buildChat(boolean conversate) {\n        addSelectorRow("text");',
    '    private void buildChat(boolean conversate) {\n        String category = "code".equals(currentMode) ? "code" : "text";\n        addSelectorRow(category);\n        if (!conversate) content.addView(text(("code".equals(category) ? "CODE" : "TEXT") + " models ranked strongest → lighter", 12, MUTED, false), marginTop(dp(5)));',
    "chat category"
)

main = replace_once(
    main,
    'selectedModel = null; refreshSelectorLabels("text");',
    'selectedModel = null; refreshSelectorLabels(category);',
    "uncensored category refresh"
)

main = replace_once(
    main,
    'promptInput = edit("Talk, research, code, analyse…");',
    'promptInput = edit("code".equals(category) ? "Describe what you want built, debugged or reviewed…" : "Talk, research, analyse…");',
    "code prompt"
)

main = replace_once(
    main,
    '        content.addView(desc, marginTop(dp(8)));\n        EditText prompt = edit(video ? "Describe the video…" : "Describe the image…");',
    '        content.addView(desc, marginTop(dp(8)));\n        content.addView(text((video ? "VIDEO" : "IMAGE") + " models ranked strongest → lighter", 12, MUTED, false), marginTop(dp(4)));\n        EditText prompt = edit(video ? "Describe the video…" : "Describe the image…");',
    "media ranking note"
)

main = replace_once(
    main,
    '        if (!ensureSelection("text")) return;',
    '        String category = (!speak && "code".equals(currentMode)) ? "code" : "text";\n        if (!ensureSelection(category)) return;',
    "send category"
)

main = replace_once(
    main,
    '        String[] items = new String[candidates.size()]; for (int i=0;i<candidates.size();i++) items[i] = candidates.get(i).displayName();\n        new AlertDialog.Builder(this).setTitle("Choose model").setItems(items, (d, which) -> { selectedModel = candidates.get(which); refreshSelectorLabels(wantedType); }).show();',
    '        String[] items = new String[candidates.size()];\n        for (int i=0;i<candidates.size();i++) items[i] = (i + 1) + ". " + candidates.get(i).displayName();\n        new AlertDialog.Builder(this).setTitle("Choose " + wantedType.toUpperCase(Locale.US) + " model · strongest first").setItems(items, (d, which) -> { selectedModel = candidates.get(which); refreshSelectorLabels(wantedType); }).show();',
    "model chooser"
)

old_eligible = '''    private List<ModelInfo> eligibleModels(ProviderConfig p, String wantedType) {
        List<ModelInfo> out = new ArrayList<>(); boolean unc = uncensoredOnly != null && uncensoredOnly.isChecked() && "text".equals(wantedType);
        for (ModelInfo m : modelsFor(p)) if (m.enabled && wantedType.equals(m.type) && (!unc || m.isUncensored())) out.add(m);
        Collections.sort(out, Comparator.comparing((ModelInfo m) -> !m.isUncensored()).thenComparing(m -> m.name.toLowerCase(Locale.US)));
        return out;
    }
'''
new_eligible = '''    private List<ModelInfo> eligibleModels(ProviderConfig p, String wantedType) {
        List<ModelInfo> out = new ArrayList<>();
        boolean textLike = "text".equals(wantedType) || "code".equals(wantedType);
        boolean unc = uncensoredOnly != null && uncensoredOnly.isChecked() && textLike;
        for (ModelInfo m : modelsFor(p)) {
            if (m.enabled && ModelRanker.matchesCategory(m, wantedType) && (!unc || m.isUncensored())) out.add(m);
        }
        Collections.sort(out, (a, b) -> {
            int byRank = Integer.compare(ModelRanker.score(b, wantedType), ModelRanker.score(a, wantedType));
            return byRank != 0 ? byRank : a.name.compareToIgnoreCase(b.name);
        });
        return out;
    }
'''
main = replace_once(main, old_eligible, new_eligible, "ranked eligible models")

main = replace_once(
    main,
    'if (selectedModel == null || !type.equals(selectedModel.type)) {',
    'if (selectedModel == null || !ModelRanker.matchesCategory(selectedModel, type)) {',
    "ensure category"
)

main = replace_once(
    main,
    '        if (selectedProvider != null && (selectedModel == null || !type.equals(selectedModel.type) || (uncensoredOnly != null && uncensoredOnly.isChecked() && type.equals("text") && !selectedModel.isUncensored()))) {',
    '        boolean uncFilter = uncensoredOnly != null && uncensoredOnly.isChecked() && ("text".equals(type) || "code".equals(type));\n        if (selectedProvider != null && (selectedModel == null || !ModelRanker.matchesCategory(selectedModel, type) || (uncFilter && !selectedModel.isUncensored()))) {',
    "selector category"
)

main_path.write_text(main)
print("Applied Ronin Vanta v0.2 strongest-first model ranking update")
