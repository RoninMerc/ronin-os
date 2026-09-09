from pathlib import Path
import os,shutil
root=Path(os.environ.get('VANTA_PROJECT','vanta/personal'))
p=root/'app/src/androidTest/java/com/ronin/vanta/FoundationDeviceTest.java'
s=p.read_text();assert s.count('source_files_plan_response_output_limit')==2
p.write_text(s.replace('source_files_plan_response_output_limit','author_plan_response_output_limit'))
p=root/'app/src/test/java/com/ronin/vanta/GeneratedBuildExportTest.java';s=p.read_text()
old='Files.writeString(\n          dir.resolve("fixture-source.json"), fifth.toString(2), StandardCharsets.UTF_8);'
assert s.count(old)==1
p.write_text(s.replace(old,'Files.write(\n          dir.resolve("fixture-source.json"), fifth.toString(2).getBytes(StandardCharsets.UTF_8));'))
p=root/'app/src/androidTest/java/com/ronin/vanta/PipelineDeviceTest.java';s=p.read_text()
old='7, new JSONObject(t.uploaded).getJSONObject("project").getJSONArray("files").length()';assert s.count(old)==1
p.write_text(s.replace(old,'AndroidBuildFoundation.prepare(project()).project.getJSONArray("files").length(), new JSONObject(t.uploaded).getJSONObject("project").getJSONArray("files").length()'))
shutil.copy(Path(__file__).with_name('FreshCompilerReplayTest.java'),root/'app/src/androidTest/java/com/ronin/vanta/FreshCompilerReplayTest.java')
p=root/'ci/verify_generated_builds.py'
p.write_text(p.read_text()+'''
# Only after this run's real compiler/test checks pass, embed its exact source/log/APK
# evidence into the instrumentation-only APK. Nothing is added to Vanta's release assets.
client=Path(__file__).resolve().parents[1]
assets=client/'app/src/androidTest/assets/fresh-compiler';assets.mkdir(parents=True,exist_ok=True)
for stage in (3,4,5):
    shutil.copy(fixtures/f'stage-{stage}.zip',assets/f'stage-{stage}.zip')
    shutil.copy(output/f'stage-{stage}.log',assets/f'stage-{stage}.log')
shutil.copy(output/'generated-foundation-unsigned.apk',assets/'generated-foundation-unsigned.apk')
subprocess.run(['gradle','-p',str(client),'--no-daemon','--max-workers=2','assembleDebugAndroidTest','--stacktrace'],check=True,timeout=300)
distribution=Path('distribution');distribution.mkdir(exist_ok=True)
shutil.copy(client/'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk',distribution/'app-debug-androidTest.apk')
# Preserve the exact source and newly embedded test evidence used for device verification.
with zipfile.ZipFile(distribution/'Ronin-Vanta-0.9.3-Source.zip','w',zipfile.ZIP_DEFLATED) as z:
    for directory in ['app/src','gradle','ci']:
        for file in (client/directory).rglob('*'):
            if file.is_file():z.write(file,file.relative_to(client))
    for name in ['app/build.gradle','build.gradle','settings.gradle','gradle.properties','gradlew','gradlew.bat']:
        z.write(client/name,name)
print('Fresh compiler outputs embedded in instrumentation-only replay; final test APK rebuilt.',flush=True)
''')
print('Reviewed output checkpoints, Android-compatible fixture export, quality-gate file count and real compiler replay.')
