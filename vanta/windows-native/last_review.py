from pathlib import Path
root=Path('ronin-vanta-windows')
def replace(path,old,new):
 p=root/path;s=p.read_text(encoding='utf-8');assert s.count(old)==1,(path,s.count(old),old[:60]);p.write_text(s.replace(old,new),encoding='utf-8')
replace('tests/Vanta.Tests/CoreTests.cs','MediaSignatures.GenerateImage(b)','MediaSignatures.Image(b)')
replace('src/Vanta.Windows/MainWindow.cs','API-connected · 0.1.0','API-connected · 0.1.1')
replace('src/Vanta.Windows/app.manifest','assemblyIdentity version="0.1.0.0"','assemblyIdentity version="0.1.1.0"')
replace('src/Vanta.Windows/ChatPage.cs','var quote = await Services.QuoteVideoAsync(selected, video, System.Threading.CancellationToken.None);','''var quotedModel=selected;var quotedOptions=JsonEx.Clone(video);
            var quote = await Services.QuoteVideoAsync(quotedModel, quotedOptions, System.Threading.CancellationToken.None);
            if(Mode!="video"||selected?.Key!=quotedModel.Key){Shell.Notice("Video selection changed; no generation was submitted.");return;}''')
replace('src/Vanta.Windows/ChatPage.cs','selected.Name + " · " + video.Duration + " · " + video.Resolution','quotedModel.Name + " · " + quotedOptions.Duration + " · " + quotedOptions.Resolution')
replace('src/Vanta.Windows/ChatPage.cs','job = Services.GenerateVideo(selected, text, video);','job = Services.GenerateVideo(quotedModel, text, quotedOptions);')
replace('tests/Vanta.Tests/DesktopTests.cs','            await Test("Large conversation view recycling and scrolling",','''            await Test("All-model picker hands actual selection to chat",async()=>{
                var chooser=new ModelsPage(window,"all",m=>window.Handoff("chat",m.Key,"Selected model verification"));chooser.Enter();await chooser.FilterTask;chooser.Catalogue.SelectedItem=chooser.Catalogue.Items.Cast<ModelRecord>().Single(m=>m.Id==ModelAdapter.DefaultId);
                var select=typeof(ModelsPage).GetMethod("Select",BindingFlags.NonPublic|BindingFlags.Instance)!;select.Invoke(chooser,null);chooser.Shutdown();await Layout();Equal("Chat",window.PageName);Equal("Selected model verification",window.ChatView.Composer.Input.Text);
            });
            await Test("Large conversation view recycling and scrolling",''')
print('Final compiler contract and model-picker/video snapshot regressions applied.')
