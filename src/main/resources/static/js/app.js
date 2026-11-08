// SafeKeep Support Agent UI. All actions call the REST API with the CSRF header.
(function () {
  const meta = n => (document.querySelector(`meta[name="${n}"]`) || {}).content;
  const csrfHeader = meta('_csrf_header'), csrfToken = meta('_csrf');

  async function api(method, url, body) {
    const headers = { 'Content-Type': 'application/json' };
    if (csrfHeader && csrfToken) headers[csrfHeader] = csrfToken;
    const res = await fetch(url, { method, headers, body: body ? JSON.stringify(body) : undefined });
    let data = null;
    try { data = await res.json(); } catch (e) { /* empty body */ }
    if (!res.ok) throw new Error((data && (data.message || data.error)) || `Request failed (${res.status})`);
    return data;
  }

  function toast(html, kind) {
    const t = document.getElementById('toast');
    if (!t) return alert(html.replace(/<[^>]+>/g, ''));
    t.innerHTML = html;
    t.className = 'toast ' + (kind || '');
    t.hidden = false;
    clearTimeout(t._timer);
    t._timer = setTimeout(() => { t.hidden = true; }, 6000);
  }

  const reload = (ms) => setTimeout(() => location.reload(), ms || 300);

  document.addEventListener('click', async (ev) => {
    const row = ev.target.closest('tr.row-link');
    if (row) { location.href = row.dataset.href; return; }

    const el = ev.target.closest('[data-action]');
    if (!el || el.type === 'checkbox') return;
    const a = el.dataset.action;
    el.disabled = true;
    try {
      if (a === 'send') {
        const r = await api('POST', '/api/simulator/send', { scenarioId: el.dataset.scenario });
        const verb = { CREATED: 'Created', LINKED: 'Linked to existing', DUPLICATE_IGNORED: 'Duplicate ignored for' }[r.status] || r.status;
        toast(`${verb} <a href="/investigations/${r.investigationId}">${r.investigation}</a>`, 'ok');
      } else if (a === 'reset') {
        await api('POST', '/api/simulator/reset', { scenarioId: el.dataset.scenario });
        toast(`Scenario ${el.dataset.scenario} data reset`, 'ok');
      } else if (a === 'approve') {
        toast('Executing action…');
        const p = await api('POST', `/api/investigations/${el.dataset.id}/proposals/${el.dataset.pid}/approve`);
        toast(`${p.actionName}: ${p.status}`, p.status === 'SUCCEEDED' ? 'ok' : 'bad');
        reload(800);
      } else if (a === 'reject') {
        const reason = prompt('Reason for rejecting (optional):') || '';
        await api('POST', `/api/investigations/${el.dataset.id}/proposals/${el.dataset.pid}/reject`, { reason });
        reload();
      } else if (a === 'reinvestigate') {
        await api('POST', `/api/investigations/${el.dataset.id}/reinvestigate`);
        reload();
      } else if (a === 'feedback') {
        await api('POST', `/api/investigations/${el.dataset.id}/feedback`, { useful: el.dataset.useful === 'true' });
        reload();
      }
    } catch (e) {
      toast(e.message, 'bad');
    } finally {
      el.disabled = false;
    }
  });

  // Admin toggles
  document.addEventListener('change', async (ev) => {
    const el = ev.target;
    if (el.dataset.action !== 'toggle') return;
    try {
      await api('PATCH', `/api/applications/${el.dataset.app}/tools/${el.dataset.name}`, { enabled: el.checked });
      toast(`${el.dataset.name} ${el.checked ? 'enabled' : 'disabled'}`, 'ok');
    } catch (e) {
      el.checked = !el.checked;
      toast(e.message, 'bad');
    }
  });

  // Conversation composer
  const composer = document.getElementById('composer');
  if (composer) {
    let kind = 'QUESTION';
    composer.querySelectorAll('button[data-kind]').forEach(b => b.addEventListener('click', () => { kind = b.dataset.kind; }));
    composer.addEventListener('submit', async (ev) => {
      ev.preventDefault();
      const text = composer.text.value.trim();
      if (!text) return;
      const buttons = composer.querySelectorAll('button');
      buttons.forEach(b => b.disabled = true);
      if (kind === 'QUESTION') toast('Asking the Agent…');
      try {
        await api('POST', `/api/investigations/${composer.dataset.id}/messages`, { type: kind, text });
        reload();
      } catch (e) {
        toast(e.message, 'bad');
        buttons.forEach(b => b.disabled = false);
      }
    });
  }

  // Live progress on the investigation page (Server-Sent Events)
  const page = document.getElementById('investigation');
  if (page && page.dataset.open === 'true' && window.EventSource) {
    const tl = document.getElementById('timeline');
    const es = new EventSource(`/api/investigations/${page.dataset.id}/stream`);
    const add = (type, msg) => {
      const li = document.createElement('li');
      li.className = 'live tl-' + type;
      const now = new Date().toLocaleTimeString([], { hour12: false });
      li.innerHTML = `<span class="tl-time">${now}</span><span class="tl-text"><strong></strong> <span></span></span>`;
      li.querySelector('strong').textContent = type.charAt(0).toUpperCase() + type.slice(1);
      li.querySelector('span span').textContent = msg;
      tl.appendChild(li);
    };
    ['tool', 'analysis', 'event', 'proposal', 'action', 'state'].forEach(t =>
      es.addEventListener(t, e => {
        const data = JSON.parse(e.data);
        add(t, data.message);
        if (t === 'state' && /COMPLETED|WAITING_FOR_INFO|UNABLE_TO_COMPLETE/.test(data.message)) { es.close(); reload(600); }
      }));
  }

  // Auto-refresh the list while something is still running
  const list = document.querySelector('[data-autorefresh="true"]');
  if (list) setTimeout(() => location.reload(), 5000);
})();
