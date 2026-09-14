from pathlib import Path
r=Path('ronin-vanta-windows')
def change(rel,old,new):
 p=r/rel;s=p.read_text(encoding='utf-8');assert s.count(old)==1,(rel,s.count(old),old[:80]);p.write_text(s.replace(old,new),encoding='utf-8',newline='\n')
change('src/Vanta.Core/ImageEditing.cs','(a,b)=>c.Transfer("Receiving edited image",a,b)','(a,b)=>c.Transfer(a,b)')
p=r/'src/Vanta.Windows/NativeVideoTools.cs';s=p.read_text();p.write_text(s.replace('using Windows.','using global::Windows.'),encoding='utf-8',newline='\n')
change('src/Vanta.Windows/ChatUnified.cs','private void InitializeActions(StackPanel toolbar)','private void InitializeActions(Panel toolbar)')
change('tests/Vanta.Tests/UnifiedTests.cs','var a=Model(),b=JsonEx.Clone(a);','var a=Model();var b=JsonEx.Clone(a);')
change('tests/Vanta.Tests/UnifiedTests.cs','Windows.UI.Color.FromArgb','global::Windows.UI.Color.FromArgb')
print('Corrected transfer, responsive Panel and actual test compiler diagnostics.')
