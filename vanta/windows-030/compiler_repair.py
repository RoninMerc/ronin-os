from pathlib import Path
p=Path('ronin-vanta-windows/src/Vanta.Core/Projects.cs')
s=p.read_text(encoding='utf-8')
old='public sealed record CommandResult(int ExitCode, string Log, TimeSpan Elapsed);'
if old not in s:
    raise SystemExit('Projects CommandResult declaration not found')
s=s.replace(old,'public sealed record ProjectCommandResult(int ExitCode, string Log, TimeSpan Elapsed);',1)
s=s.replace('public static async Task<CommandResult> RunAsync(', 'public static async Task<ProjectCommandResult> RunAsync(',1)
# Constructor use is scoped to the same runner method.
s=s.replace('return new CommandResult(', 'return new ProjectCommandResult(',1)
p.write_text(s,encoding='utf-8')
print('Renamed legacy project command result to avoid agentic CommandResult collision.')

# Rebuild trigger: verify repaired native Windows 0.3 before promoting to 0.4.
