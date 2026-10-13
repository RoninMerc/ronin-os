from pathlib import Path
p=Path("mother-base/Program.cs")
s=p.read_text()
s=s.replace('ApplicationConfiguration.Initialize();','ApplicationConfiguration.Initialize();\n  if(args.Contains("--screenshots")) { using var f=new MainWindow(); f.CaptureScreens(); return; }',1)
method=r'''
 public void CaptureScreens(){
  Directory.CreateDirectory("mother-base/screenshots");
  Show();poll.Stop();
  ShowVehicles(JsonNode.Parse("""{"vehicles":[{"plate":"DEMO123","revision":2,"details":{"make":"Toyota","model":"Corolla","colour":"Silver","vehicle":"Toyota Corolla","address":"12 Example Street","breach_level":2}},{"plate":"DEMO456","revision":1,"details":{"make":"Ford","model":"Ranger","colour":"White","vehicle":"Ford Ranger","address":"34 Example Road","breach_level":1}}]}""")!);
  FillCheckpoints(JsonNode.Parse("""{"checkpoints":[{"id":"demo-1","name":"Front gate","group":"Example route","order":1,"lat":-27.47,"lon":153.02,"radius":25},{"id":"demo-2","name":"Visitor parking","group":"Example route","order":2,"lat":-27.471,"lon":153.021,"radius":20},{"id":"demo-3","name":"Rear entrance","group":"Example route","order":3,"lat":-27.472,"lon":153.022,"radius":25}]}""")!);
  patrols.Text="SAMPLE DATA — interface preview only\r\n\r\n"+Pretty(JsonNode.Parse("""{"devices":[{"role":"P1","status":"example connected","lastContact":"example only"},{"role":"P2","status":"example connected","lastContact":"example only"},{"role":"P3","status":"example offline","lastContact":"example only"}]}""")!);
  status.Text="SAMPLE DATA — actual Windows interface; no production connection or credentials.";
  var tabs=Controls.OfType<TabControl>().Single();
  foreach(var item in new[]{(2,"01-vehicles"),(5,"02-checkpoints"),(0,"03-patrols"),(6,"04-settings")}){
   tabs.SelectedIndex=item.Item1;PerformLayout();Refresh();Application.DoEvents();poll.Stop();
   using var bitmap=new Bitmap(Width,Height);DrawToBitmap(bitmap,new Rectangle(0,0,Width,Height));
   bitmap.Save("mother-base/screenshots/"+item.Item2+".png",System.Drawing.Imaging.ImageFormat.Png);
  }
  Close();
 }
'''
s=s.replace(' protected override void Dispose(bool disposing)',method+'\n protected override void Dispose(bool disposing)')
p.write_text(s)
