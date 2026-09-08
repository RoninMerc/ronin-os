from pathlib import Path
root=Path('ronin-vanta-windows')
def patch(file,old,new):
 p=root/file;s=p.read_text();assert s.count(old)==1,(file,s.count(old));p.write_text(s.replace(old,new))
patch('src/Vanta.Windows/Ui.cs','{ Grid.SetRow(element, row); Grid.SetColumn(element, column);','{ System.Windows.Controls.Grid.SetRow(element, row); System.Windows.Controls.Grid.SetColumn(element, column);')
patch('src/Vanta.Core/ToolPreparation.cs','if (release["sdk"] is JsonObject primary)','if (release is JsonObject releaseObject && releaseObject["sdk"] is JsonObject primary)')
patch('src/Vanta.Core/Domain.cs','PropertyNameCaseInsensitive = true, WriteIndented = false','PropertyNameCaseInsensitive = true, WriteIndented = false, IgnoreReadOnlyProperties = true')
p=root/'Directory.Build.props';p.write_text(p.read_text().replace('Ronin Group Australia','Ronin'))
print('Native compiler errors corrected; computed model fields excluded from persisted payloads.')
