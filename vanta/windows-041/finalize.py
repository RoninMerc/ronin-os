from pathlib import Path
root=Path('ronin-vanta-windows')
def edit(name,old,new,count=1):
    path=root/name; text=path.read_text(encoding='utf-8')
    if text.count(old)!=count: raise RuntimeError(f'Unexpected source while finalising {name}: {old[:100]}')
    path.write_text(text.replace(old,new),encoding='utf-8')
edit('src/Vanta.Core/CatalogueSync.cs','while (due > options.Now()) await options.Delay(TimeSpan.FromSeconds(Math.Min(60, (due - options.Now()).TotalSeconds)), ct);','''while (true)
                    {
                        var remaining = due - options.Now();
                        if (remaining <= TimeSpan.Zero) break;
                        await options.Delay(TimeSpan.FromSeconds(Math.Min(60, remaining.TotalSeconds)), ct);
                    }''')
edit('src/Vanta.Core/Jobs.cs','if (cancellation.ContainsKey(id)) return;','if (cancellation.ContainsKey(id)) { if (Get(id)?.Type == "catalogue") return; throw new VantaException("This task is already running."); }')
edit('src/Vanta.Core/Jobs.cs','''if (j.Type == "catalogue") { j.State = "Waiting for provider"; j.Error = ""; j.RequestInFlight = false; j.Progress = ProgressValue.Unknown("Waiting for provider", "Read-only catalogue discovery will resume from its saved page and cooldown."); }''','''if (j.Type == "catalogue" && store.Read<JsonObject>("job-result", j.Id) is JsonObject savedCatalogue && savedCatalogue.Str("_terminal") == "Complete") { j.State = "Complete"; j.Error = ""; j.RequestInFlight = false; j.Progress = ProgressValue.Complete(); }
            else if (j.Type == "catalogue") { j.State = j.Remote.Flag("catalogue_user_cancelled") ? "Cancelled" : "Waiting for provider"; j.Error = ""; j.RequestInFlight = false; j.Progress = ProgressValue.Unknown(j.State, j.State == "Cancelled" ? "Catalogue refresh was cancelled by the user. Saved pages remain available." : "Read-only catalogue discovery will resume from its saved page and cooldown."); }''')
edit('src/Vanta.Windows/SettingsPage.cs','0.3.0','0.4.1',2)
edit('src/Vanta.Windows/MainWindow.cs','Desktop Agent · 0.4.0','Desktop Agent · 0.4.1')
edit('src/Vanta.Windows/app.manifest','version="0.3.0.0"','version="0.4.1.0"')
edit('tests/Vanta.Tests/Program.cs','await CatalogueTests(); await WorkflowTests(); await DesktopTests();','await CatalogueTests(); await WorkflowTests(); await DesktopTests(); await CatalogueAcceptanceTests();')
(root/'tests/Vanta.Tests/CatalogueAcceptanceTests.cs').write_text(r'''using System;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using Vanta.Core;
using Vanta.Windows;

namespace Vanta.Tests;
public static partial class Program
{
    static async Task CatalogueAcceptanceTests()
    {
        await Test("Cooldown deadline crossing cannot create a negative Task.Delay", async () =>
        {
            using var store = new Store(Profile()); int calls = 0; var now = DateTimeOffset.UtcNow;
            var timing = new CatalogueSyncOptions { Now = () => now += TimeSpan.FromMilliseconds(20), FirstRetry = TimeSpan.FromMilliseconds(50), MaximumRetry = TimeSpan.FromMilliseconds(50), PageSpacing = TimeSpan.Zero, JitterSeconds = () => 0, Delay = (delay, ct) => { True(delay >= TimeSpan.Zero); now += delay; return Task.CompletedTask; } };
            using var api = new ProviderApi(_ => "fixture", Sync(_ => ++calls == 1 ? CatalogueError() : CataloguePage()));
            await new ModelRegistry(store, timing).RefreshAsync(Provider.Get("featherless"), api, null, CancellationToken.None); Equal(2, calls);
        });
        await Test("A durably completed catalogue is not re-read after interruption during finalisation", () =>
        {
            using var store = new Store(Profile()); var record = new JobRecord { Type = "catalogue", State = "Running", ModelKey = "featherless" };
            store.Save("job", record.Id, record); store.Save("job-result", record.Id, new System.Text.Json.Nodes.JsonObject { ["_terminal"] = "Complete", ["text"] = "saved" });
            using var engine = new JobEngine(store); Equal("Complete", engine.Get(record.Id)!.State); Equal(100d, engine.Get(record.Id)!.Progress.Percent!.Value);
        });
        await Test("Paid jobs retain duplicate-start rejection", async () =>
        {
            using var store = new Store(Profile()); using var engine = new JobEngine(store);
            var record = engine.Create("image", "Paid job fixture", "fixture", new());
            engine.Start(record.Id, async c => await Task.Delay(Timeout.InfiniteTimeSpan, c.Token));
            Reject<VantaException>(() => engine.Start(record.Id, c => Task.CompletedTask));
            engine.Cancel(record.Id); await Finish(engine, record.Id); await engine.StopAsync();
        });
        await Test("Actual Activity UI shows one recovering task, preserves history, then completes", async () =>
        {
            string profile = Profile(); var release = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously); var waiting = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
            using (var store = new Store(profile))
            {
                store.SetKey("featherless", "fixture-not-a-live-key"); store.Save("catalogue", "featherless", new[] { Model() }, false);
                using var engine = new JobEngine(store);
                for (int n = 0; n < 3; n++) { var job = engine.Create("catalogue", "Featherless AI catalogue", "featherless", new() { ["provider"] = "featherless" }); engine.Update(job.Id, j => { j.State = "Action required"; j.Error = "The provider's rate or concurrency limit was reached."; }); }
            }
            var now = DateTimeOffset.UtcNow; int calls = 0;
            var timing = new CatalogueSyncOptions { Now = () => now, PageSpacing = TimeSpan.Zero, JitterSeconds = () => 0, Delay = async (delay, ct) => { waiting.TrySetResult(); await release.Task.WaitAsync(ct); now += delay; } };
            services = new VantaServices(profile, Sync(_ => ++calls == 1 ? CatalogueError() : CataloguePage()), timing);
            services.Preferences.TrayEnabled = false;
            window = new MainWindow(services) { TestLifetime = true, Width = 1440, Height = 920, Left = 0, Top = 0 };
            Application.Current.MainWindow = window;
            try
            {
                await waiting.Task.WaitAsync(TimeSpan.FromSeconds(10)); window.Show(); window.Navigate("Activity"); await Layout();
                var job = services.Jobs.Snapshot().Single(j => j.Type == "catalogue" && j.State != "Superseded");
                var page = (ActivityPage)Page(); page.Select(job.Id); await Layout();
                Equal("Waiting for provider", job.State); Equal("", job.Error); Equal(3, page.Tasks.Items.Count); Equal(1, calls);
                True(Descendants<TextBlock>(page).Any(t => t.Text == "Waiting for provider"));
                True(!Descendants<TextBlock>(page).Any(t => t.Text == "Waiting for provider · Waiting for provider"));
                True(!Descendants<Button>(page).Any(b => b.Content?.ToString() == "Resume saved work"));
                Shot("catalogue-429-waiting");
                var filter = Descendants<ComboBox>(page).Single(); filter.SelectedItem = "Needs attention"; await Layout(); Equal(0, page.Tasks.Items.Count); filter.SelectedItem = "All tasks";
                release.TrySetResult(); Equal("Complete", (await Finish(services.Jobs, job.Id)).State); page.Select(job.Id); await Layout(); Shot("catalogue-recovered"); Equal(2, calls);
                window.Navigate("Settings"); ((SettingsPage)Page()).Section("About & diagnostics"); await Layout(); True(Descendants<TextBlock>(window).Any(t => t.Text.Contains("Windows · 0.4.1"))); Shot("version-041");
            }
            finally
            {
                release.TrySetResult(); await services.Jobs.StopAsync();
                if (window.IsVisible) window.Close(); services.Dispose(); services = null; window = null;
            }
        });
    }
}
''',encoding='utf-8')
print('Applied final deadline, completion recovery, version-label and native Activity acceptance checks.')
