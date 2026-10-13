/* Ronin Patrol Link reader v2.
   Read-only DOM adapter. It reads only visible issue-monitor text, never cookies,
   storage, input values or page scripts. */
(function (guardIds) {
    'use strict';
    const norm = x => String(x == null ? '' : x).replace(/[\u200B-\u200D\uFEFF]/g, '').replace(/\s+/g, ' ').trim();
    const key = x => norm(x).toUpperCase();
    const allowed = new Set((guardIds || []).map(key));
    const guardKey = x => key(x).replace(/[. _-]/g, '');
    const canonical = new Map((guardIds || []).map(g => [guardKey(g),norm(g)]));
    const creator = value => canonical.get(guardKey(value)) || norm(value);
    const out = {schema: 5, login: false, recognized: false, loading: false, ready: document.readyState, emptyConfirmed: false, totalRows: 0, selectedRows: [], allRows: [], unreadRows: 0, mode: 'none', idCandidates: 0};
    const text = norm(document.body && (document.body.innerText || document.body.textContent));
    const notHidden = e => { if (!e) return false; for(let p=e;p&&p.nodeType===1;p=p.parentElement){const s=getComputedStyle(p);if(s.display==='none'||s.visibility==='hidden')return false;}return true; };
    const hasMonitor = /\bISSUE\s+MONITOR\b/i.test(text);

    // Some Silvertracker pages keep password controls in dialogs/templates even while
    // the Issue Monitor is open. Only call this a login page when the monitor itself
    // is absent and the page genuinely looks like a sign-in screen.
    const passwordPresent = !!document.querySelector('input[type="password"]');
    const loginWords = /\b(sign\s*in|log\s*in|username|password)\b/i.test(text);
    out.login = !hasMonitor && passwordPresent && loginWords;
    if (out.login) return JSON.stringify(out);

    out.loading = Array.from(document.querySelectorAll('.k-loading-mask,.rgLoading,.dataTables_processing,[aria-busy="true"]')).some(notHidden);
    try { if(window.Sys && Sys.WebForms && Sys.WebForms.PageRequestManager) out.loading = out.loading || Sys.WebForms.PageRequestManager.getInstance().get_isInAsyncPostBack(); } catch(e) {}
    out.emptyConfirmed = hasMonitor && /\b(no records to display|no records found|no issues found|no data available)\b/i.test(text);

    const aliases = {
        id: ['issueid', 'issuenumber', 'tracking'],
        property: ['propertyname', 'property', 'sitename'],
        issue: ['reportedissue', 'issuetype', 'checkpoint'],
        date: ['createddate', 'datecreated', 'reporteddate'],
        guard: ['createdby', 'reportedby', 'officer', 'guard']
    };
    const issueIdRe = /^\d{7,14}$/;
    const dateRe = /\b(?:Mon|Tue|Wed|Thu|Fri|Sat|Sun)\s+\d{1,2}\/\d{1,2}(?:\/\d{2,4})?\s+\d{1,2}:\d{2}(?::\d{2})?\s*(?:AM|PM)\b/i;
    const seen = new Set();
    const handledIds = new Set();
    const idFrom = e => {
        const full=norm(e.innerText || e.textContent);
        if(issueIdRe.test(full))return full;
        const candidates=Array.from(e.querySelectorAll('a,span,label')).filter(notHidden)
            .map(n=>norm(n.innerText||n.textContent)).filter(t=>issueIdRe.test(t));
        const unique=Array.from(new Set(candidates));
        return unique.length===1?unique[0]:'';
    };
    const creatorFrom = e => {
        const full=norm(e.innerText || e.textContent);
        if(canonical.has(guardKey(full)))return creator(full);
        const values=Array.from(e.querySelectorAll('a,span,label')).filter(notHidden)
            .map(n=>norm(n.innerText||n.textContent)).filter(t=>canonical.has(guardKey(t)));
        const unique=Array.from(new Set(values.map(creator)));
        return unique.length===1?unique[0]:creator(full);
    };

    const pushRow = (id, guard, property, issue, date) => {
        id = norm(id); guard = norm(guard); property = norm(property); issue = norm(issue); date = norm(date);
        guard=creator(guard);
        const identity=[id,guard,property,issue,date].join('|');
        if (!issueIdRe.test(id) || seen.has(identity)) return false;
        seen.add(identity); handledIds.add(id); out.totalRows++;
        const record={id,guard,property:property.slice(0,240),issue:issue.slice(0,8000),date:date.slice(0,120)};
        out.allRows.push(record);
        if (allowed.has(key(guard))) out.selectedRows.push(record);
        return true;
    };

    const columnName = e => {
        const explicit = e.getAttribute && (e.getAttribute('data-field') || e.getAttribute('data-title') || e.getAttribute('aria-label'));
        const select = e.querySelector && e.querySelector('select');
        return norm(explicit || (select && select.options && select.options.length ? select.options[0].text : (e.innerText || e.textContent))).replace(/[^a-z]/gi, '').toLowerCase();
    };

    // 1. Normal HTML tables / ARIA grids.
    const containers = Array.from(document.querySelectorAll('table,[role="grid"]'));
    for (const table of containers) {
        let map = {}, found = 0;
        let headers = Array.from(table.querySelectorAll('thead tr:first-child th,thead tr:first-child td,[role="columnheader"]'));
        if (!headers.length) headers = Array.from(table.querySelectorAll('tr:first-child th'));
        headers.forEach((h, index) => {
            const name = columnName(h);
            for (const [field, names] of Object.entries(aliases)) {
                if (map[field] === undefined && names.some(n => name === n || name.startsWith(n))) { map[field] = index; found++; }
            }
        });
        const confident = found >= 4 && map.id !== undefined && map.property !== undefined && map.issue !== undefined && map.date !== undefined && map.guard !== undefined;
        const rows = Array.from(table.querySelectorAll('tr,[role="row"]'));
        for (const row of rows) {
            if (!notHidden(row)) continue;
            const cells = Array.from(row.children || []).filter(e => e.matches && e.matches('td,[role="gridcell"],[role="cell"]'));
            if (cells.length < 5) continue;
            let idx = map;
            if (!confident) {
                if (!hasMonitor || cells.length < 5 || !issueIdRe.test(idFrom(cells[0]))) continue;
                idx = {id:0, property:1, issue:2, date:3, guard:4};
            }
            if (Math.max(...Object.values(idx)) >= cells.length) continue;
            const val = f => norm(cells[idx[f]].innerText || cells[idx[f]].textContent);
            pushRow(idFrom(cells[idx.id]), creatorFrom(cells[idx.guard]), val('property'), val('issue'), val('date'));
        }
    }
    if (out.totalRows) { out.recognized = true; out.mode = 'table'; }

    // 2. Generic Silvertracker row detection. This handles layouts built from DIVs,
    //    links and ASP.NET controls instead of semantic <table> rows.
    if (hasMonitor) {
    const all = Array.from(document.querySelectorAll('a,td,div,span,p,label'));
    const idNodes = all.filter(e => {
        const t = norm(e.innerText || e.textContent);
        if (!notHidden(e) || !issueIdRe.test(t)) return false;
        // Prefer leaf-ish nodes so a parent containing a whole grid is not mistaken for one ID.
        const childIds = e.querySelectorAll ? Array.from(e.querySelectorAll('a,td,div,span,p,label')).filter(c => c !== e && issueIdRe.test(norm(c.innerText || c.textContent))) : [];
        return childIds.length === 0;
    });
    out.idCandidates = idNodes.length;

    const leafTexts = root => {
        const vals = [];
        const nodes = root.querySelectorAll ? Array.from(root.querySelectorAll('*')) : [];
        for (const e of nodes) {
            if (!notHidden(e) || (e.children && e.children.length)) continue;
            const t = norm(e.innerText || e.textContent);
            if (!t || t.length > 260) continue;
            if (!vals.length || vals[vals.length-1] !== t) vals.push(t);
        }
        return vals;
    };

    const chooseRow = node => {
        let p = node;
        for (let depth=0; p && depth<9; depth++, p=p.parentElement) {
            const vals = leafTexts(p);
            const t = vals.join(' | ');
            if (!t || t.length > 1600) continue;
            const ids = vals.filter(v => issueIdRe.test(v));
            const uniq = Array.from(new Set(ids));
            if (uniq.length !== 1 || uniq[0] !== norm(node.innerText || node.textContent)) continue;
            if (!dateRe.test(t)) continue;
            // A real issue row normally contains a Created By value. For selected guards,
            // this is exact; for other rows allow a sufficiently structured row.
            if (Array.from(allowed).some(g => t.toUpperCase().includes(g))) return p;
            if (p.children && p.children.length >= 4) return p;
        }
        return null;
    };

    for (const node of idNodes) {
        const id = norm(node.innerText || node.textContent);
        if (handledIds.has(id)) continue;
        const row = chooseRow(node);
        if (!row) continue;
        let vals = leafTexts(row);
        if (!vals.length) vals = norm(row.innerText || row.textContent).split(/\s{2,}|\n+/).map(norm).filter(Boolean);
        const joinedVals = vals.join(' | ');
        const date = (joinedVals.match(dateRe) || [''])[0];
        let guard = '';
        // Created By follows the timestamp. Never choose Assigned To or a badge
        // before the timestamp just because it matches a selected guard.
        if (!guard) {
            const di = vals.findIndex(v => date && v.includes(date));
            if (di >= 0) {
                for (let i=di+1; i<Math.min(vals.length, di+5); i++) {
                    const v = vals[i];
                    if (/^[A-Za-z0-9][A-Za-z0-9._ -]{0,39}$/.test(v) && !/^(all|assigned to)$/i.test(v)) { guard = creator(v); break; }
                }
            }
        }
        let idIndex = vals.findIndex(v => v === id);
        let dateIndex = vals.findIndex(v => date && v.includes(date));
        let between = (idIndex >= 0 && dateIndex > idIndex) ? vals.slice(idIndex+1, dateIndex) : [];
        between = between.filter(v => v !== id && !/^issue\s*id/i.test(v));
        const property = between.length ? between[0] : '';
        const issue = between.length > 1 ? between[between.length-1] : '';
        pushRow(id, guard, property, issue, date);
    }
    }
    if (out.totalRows && out.mode==='none') { out.recognized = true; out.mode = 'generic-row'; }

    // 3. Last-resort body-text parser. Silvertracker's Issue Monitor is a fixed-column
    //    feed; parse blocks between issue IDs when the DOM uses unusual custom controls.
    if (hasMonitor && document.body) {
        const lines = String(document.body.innerText || document.body.textContent || '').split(/\r?\n/).map(norm).filter(Boolean);
        for (let i=0; i<lines.length; i++) {
            const id = lines[i];
            if (!issueIdRe.test(id) || handledIds.has(id)) continue;
            let end = Math.min(lines.length, i + 14);
            for (let j=i+1; j<end; j++) if (issueIdRe.test(lines[j])) { end = j; break; }
            const block = lines.slice(i, end);
            const joined = block.join(' | ');
            const dm = joined.match(dateRe); const date = dm ? dm[0] : '';
            if (!date) continue;
            let guard = '';
            const dateLine = block.findIndex(v => date && v.includes(date));
            if(dateLine>=0 && dateLine+1<block.length)guard=creator(block[dateLine+1]);
            const property = block.length > 1 ? block[1] : '';
            const issue = dateLine > 2 ? block[dateLine-1] : (block.length > 2 ? block[2] : '');
            pushRow(id, guard, property, issue, date);
        }
    }
    if (out.totalRows) { out.recognized = true; if(out.mode==='none')out.mode='body-text'; }
    else out.recognized = hasMonitor;
    const visibleIds=new Set(Array.from(document.querySelectorAll('a,td,span,label'))
        .filter(notHidden).map(e=>norm(e.innerText||e.textContent)).filter(t=>issueIdRe.test(t)));
    out.unreadRows=Array.from(visibleIds).filter(id=>!handledIds.has(id)).length;
    return JSON.stringify(out);
})(__GUARD_IDS__)
