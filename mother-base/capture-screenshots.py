from pathlib import Path
p=Path("mother-base/Program.cs")
s=p.read_text()
s=s.replace('ApplicationConfiguration.Initialize();','ApplicationConfiguration.Initialize();\n  if(args.Contains("--screenshots")) { using var f=new MainWindow(); f.CaptureScreens(); return; }',1)
method=r'''
 public void CaptureScreens(){
  Directory.CreateDirectory("mother-base/screenshots-v03");
  Show();poll.Stop();
  ShowVehicles(JsonNode.Parse("""{"vehicles":[{"plate":"DEMO456","revision":1,"details":{"make":"Ford","model":"Ranger","colour":"White","vehicle":"Ford Ranger","address":"34 Example Road","breach_level":1}},{"plate":"DEMO123","revision":2,"details":{"make":"Toyota","model":"Corolla","colour":"Silver","vehicle":"Toyota Corolla","address":"12 Example Street","breach_level":2}}]}""")!);
  if(vehicles.Rows.Count!=2||Convert.ToString(vehicles.Rows[0].Cells["plate"].Value)!="DEMO123")throw new Exception("Registration sorting failed");
  vehicleSearch.Text="DEMO456";
  if(vehicles.Rows.Count!=1||Convert.ToString(vehicles.Rows[0].Cells["plate"].Value)!="DEMO456")throw new Exception("Registration search failed");
  vehicleSearch.Text="";
  if(vehicleRecords.Count!=2||vehicles.Rows.Count!=2)throw new Exception("Search must preserve records");
  FillCheckpoints(JsonNode.Parse("""{"checkpoints":[{"id":"demo-1","name":"Front gate","group":"PBC1","order":1,"lat":-27.47,"lon":153.02,"radius":25},{"id":"demo-2","name":"Visitor parking","group":"PBC1","order":2,"lat":-27.471,"lon":153.021,"radius":20},{"id":"demo-3","name":"Rear entrance","group":"PBC2","order":1,"lat":-27.472,"lon":153.022,"radius":25}]}""")!);
  ShowHistory(JsonNode.Parse("""{"breaches":[{"source_id":"demo-report-1","plate":"DEMO123","revision":2,"event":{"warningLevel":2,"property":"Example property","sourceDate":"2026-10-13T03:10:00Z","vehicle":"Toyota Corolla","colour":"Silver","address":"12 Example Street","parking":"visitor","notes":"Sample report for interface preview"}}]}""")!);
  ShowReview(JsonNode.Parse("""{"review":[{"source_id":"demo-review","reason":"Parking area needs confirmation","received_at":"2026-10-13T03:10:00Z","source":{"vehicle":{"registration":"DEMO789","colour":"Blue"},"issue":"Parking Breach - 1st Warning","property":"Example property"}}]}""")!);
  ShowActivity(JsonNode.Parse("""{"rows":[{"guard":"Example guard","property":"Example property","issue":"Parking Breach - 2nd Warning","date":"13/10/2026 13:10"}]}""")!);
  ShowPatrols(JsonNode.Parse("""{"patrols":[{"patrolId":"P1","onDuty":true,"state":"STATIONARY","receivedAt":1791861000000,"accuracy":8,"lat":-27.47,"lon":153.02,"versionName":"1.1.50"},{"patrolId":"P2","onDuty":false,"state":"UNKNOWN","receivedAt":0},{"patrolId":"P3","onDuty":false,"state":"UNKNOWN","receivedAt":0}]}""")!);
  if(history.Rows.Count!=1||review.Rows.Count!=1||patrols.Rows.Count!=3)throw new Exception("Human-readable report projections failed");
  foreach(var name in serviceStates.Keys){SetConnection(name,true);serviceStates[name].Text="Sample data • not a live connection";serviceStates[name].ForeColor=ThemeMuted;}
  status.Text="SAMPLE DATA — actual Windows interface. No production connection or credentials.";
  foreach(var item in new[]{("Overview","01-overview"),("Vehicles","02-vehicles"),("Checkpoints","03-checkpoints"),("Patrol fleet","04-patrols"),("Breach history","05-breach-history"),("Import review","06-import-review"),("Settings","07-settings")}){
   Navigate(item.Item1);PerformLayout();Refresh();Application.DoEvents();poll.Stop();
   if(!pages[item.Item1].Visible)throw new Exception("Navigation did not show "+item.Item1);
   using var bitmap=new Bitmap(Width,Height);DrawToBitmap(bitmap,new Rectangle(0,0,Width,Height));
   bitmap.Save("mother-base/screenshots-v03/"+item.Item2+".png",System.Drawing.Imaging.ImageFormat.Png);
  }
  Console.WriteLine("Passed: registration sorting/search, record preservation, report projections, seven-page navigation and rendering.");
  Close();
 }
'''
s=s.replace(' protected override void Dispose(bool disposing)',method+'\n protected override void Dispose(bool disposing)')
p.write_text(s)
