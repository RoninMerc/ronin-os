"""Checkpoint extraction regressions: synthetic screenshot-shaped/mixed DOM, no live session."""
import json,shutil,sys
from pathlib import Path
from playwright.sync_api import sync_playwright
root=Path(__file__).parents[1]
script_path=Path(sys.argv[1]) if len(sys.argv)>1 else root/'app/src/main/assets/extract.js'
SCRIPT=script_path.read_text().replace('__GUARD_IDS__',json.dumps(['D.DEO','D.ROGERS1','T.MURD']))
HEAD='<thead><tr>'+''.join('<th>'+x+'</th>' for x in ['Issue ID','Property Name','Reported Issue','Created Date','Created By','All','Assigned To'])+'</tr></thead>'
def row(id,guard,area='Rampage BC',issue=None,decorated=False):
    ident=f'<a>{id}</a><span aria-label="attachment">📷</span><div>{guard}</div>' if decorated else str(id)
    return f'<tr><td>{ident}</td><td>{area}</td><td>{issue or "General Patrol - "+area}</td><td>Tue 9/29 4:42 AM</td><td>{guard}</td><td>1</td><td>T.MURD</td></tr>'
def divrow(id,guard,assigned='T.MURD'):
    return f'<div class="issue"><a>{id}</a><span>Pathfinder BC</span><span>General Patrol - Pathfinder</span><span>Tue 9/29 4:23 AM</span><span>{guard}</span><span>Done</span><span>{assigned}</span></div>'
checks=0
with sync_playwright() as p:
    b=p.chromium.launch(headless=True,executable_path=shutil.which('chromium') or shutil.which('google-chrome') or None,args=['--no-sandbox'])
    page=b.new_page()
    def read(html):
        page.set_content('<h1>ISSUE MONITOR</h1>'+html)
        return json.loads(page.evaluate(SCRIPT))
    def check(ok,name):
        global checks
        assert ok,name
        checks+=1
    r=read('<table>'+HEAD+row(1146048315,'T.MURD')+'</table>'+divrow(1146048322,'D.ROGERS1')+divrow(1146048323,'D.DEO'))
    check(len(r['selectedRows'])==3,'partial table must not hide other guards in a different row layout')
    check({x['guard'] for x in r['selectedRows']}=={'D.ROGERS1','D.DEO','T.MURD'},'all three creators survive fallback')
    r=read('<table>'+HEAD+row(1146048322,'D.ROGERS1','Harbour Front BC','Harbour Front BC',True)+row(1146048323,'D.DEO',decorated=True)+'</table>')
    check(len(r['selectedRows'])==2,'camera/badge inside Issue ID cell must not hide checkpoint rows')
    check(r['selectedRows'][0]['guard']=='D.ROGERS1','decorated row uses Created By instead of Assigned To')
    r=read('<table>'+HEAD+row(1146048322,'D.<br>ROGERS1')+row(1146048323,'D.\u200bDEO')+'</table>')
    check(len(r['selectedRows'])==2,'creator line wrapping and zero-width formatting normalized')
    check({x['guard'] for x in r['selectedRows']}=={'D.ROGERS1','D.DEO'},'normalized IDs remain canonical')
    r=read('<table>'+HEAD+row(1146048322,'HSECURITY')+'</table>'+divrow(1146048323,'D.ROGERS10','D.DEO'))
    check(len(r['selectedRows'])==0,'never use assignment or partial guard names to attribute a scan')
    check(len(r['allRows'])==2,'other guards remain available for audit, not tally')
    r=read('<table>'+HEAD+row(1146048322,'T.MURD')+row(1146048322,'D.ROGERS1')+'</table>')
    check(len(r['selectedRows'])==2,'parser does not treat a reused Issue ID as a cross-guard duplicate')
    r=read('<table>'+HEAD+row(1146048322,'T.MURD')+'</table><div><a>1146099999</a><span>Incomplete record</span></div>')
    check(r['unreadRows']==1,'incomplete records are reported instead of claiming complete read')
    r=read('<table>'+HEAD+row(1146048322,'D.ROGERS1')+'</table>')
    old=json.dumps(r['allRows'])
    page.evaluate("document.querySelector('tbody tr td:nth-child(1)').textContent='1146050000';document.querySelector('tbody tr td:nth-child(5)').textContent='D.DEO';")
    newer=json.loads(page.evaluate(SCRIPT))
    check(json.dumps(newer['allRows'])!=old,'in-place page changes produce a new snapshot')
    check(newer['selectedRows'][0]['guard']=='D.DEO','new page is read without a document reload')
    page.evaluate("window.Sys={WebForms:{PageRequestManager:{getInstance:()=>({get_isInAsyncPostBack:()=>true})}}}")
    check(json.loads(page.evaluate(SCRIPT))['loading'],'ASP.NET busy state waits before accepting')
    b.close()
print(f'PASS: {checks} history DOM regression checks')
