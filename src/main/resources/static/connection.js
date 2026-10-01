(() => {
  const el = id => document.getElementById(id);
  const fields = ['host', 'port', 'username', 'password', 'basePath', 'sudoEnabled', 'sudoUsername', 'sudoPassword', 'cloneUsername', 'group'];
  let token = '';
  let busy = false;
  const status = (message, error = false) => {
    el('connection-status').textContent = message;
    el('connection-status').classList.toggle('error', error);
  };
  const invalidateKey = () => {
    token = ''; el('key-confirmed').checked = false; el('key-panel').hidden = true;
    el('replace-key').checked = false; el('replace-key-label').hidden = true;
  };
  const values = () => Object.fromEntries(fields.map(key => [key,
    key === 'sudoEnabled' ? el('conn-' + key).checked : key === 'port' ? Number(el('conn-' + key).value) : el('conn-' + key).value]));
  const settings = () => ({...values(), trustToken: token, keyConfirmed: el('key-confirmed').checked, replaceChangedKey: el('replace-key').checked});
  async function api(path, method, body) {
    const response = await fetch(path, {method, headers: {'Content-Type': 'application/json'}, body: body ? JSON.stringify(body) : undefined});
    const data = await response.json();
    if (!response.ok) throw new Error(data.detail || '요청을 처리하지 못했습니다.');
    return data;
  }
  async function action(task) {
    if (busy) return;
    busy = true;
    const controls = [...el('connection-form').querySelectorAll('input, button')];
    controls.forEach(control => control.disabled = true);
    try { await task(); } catch (error) { status(error.message, true); }
    finally { controls.forEach(control => control.disabled = false); busy = false; }
  }
  function requireKey() {
    if (!token || !el('key-confirmed').checked) throw new Error('서버 키를 조회한 뒤 지문을 확인하고 확인란을 선택하세요.');
    if (!el('replace-key-label').hidden && !el('replace-key').checked) throw new Error('서버 키 변경 이유를 확인하고 기존 키 교체를 별도로 승인하세요.');
  }
  el('conn-host').addEventListener('input', invalidateKey);
  el('conn-port').addEventListener('input', invalidateKey);
  el('conn-sudoEnabled').addEventListener('change', () => el('sudo-fields').hidden = !el('conn-sudoEnabled').checked);
  el('parse-remote').addEventListener('click', () => action(async () => {
    const data = await api('/api/git-remotes/parse', 'POST', {url: el('existing-remote').value.trim()});
    Object.entries(data).forEach(([key, value]) => el('conn-' + key).value = value);
    el('conn-password').value = ''; el('conn-sudoPassword').value = '';
    el('conn-group').value = '';
    invalidateKey(); status('주소를 가져왔습니다. 평소 저장소를 만드는 SSH 계정과 상위 경로를 확인하세요.');
  }));
  el('discover-key').addEventListener('click', () => action(async () => {
    invalidateKey(); status('서버 키를 조회하고 있습니다.');
    const data = await api('/api/connection/observe-server-key', 'POST', {host: el('conn-host').value, port: Number(el('conn-port').value)});
    token = data.token;
    el('replace-key-label').hidden = data.changed !== 'true';
    el('server-fingerprint').textContent = data.algorithm + '\n' + data.fingerprint;
    el('key-warning').textContent = data.changed === 'true' ? '주의: 저장한 서버 키와 다릅니다. 변경 이유를 관리자에게 확인한 뒤 승인하세요.' : '조회된 지문을 신뢰할 수 있는 경로로 확인하세요. 승인은 10분 동안 유효합니다.';
    el('key-panel').hidden = false; status('서버 키를 조회했습니다. 지문을 비교한 뒤 확인란을 선택하세요.');
  }));
  el('check-connection').addEventListener('click', () => {
    if (!el('connection-form').reportValidity()) return;
    action(async () => {
      requireKey(); status('SSH 인증과 생성 권한을 검사하고 있습니다.');
      const result = await api('/api/connection/check', 'POST', settings()); status(result.message);
    });
  });
  el('connection-form').addEventListener('submit', event => {
    event.preventDefault();
    action(async () => {
      requireKey(); status('연결을 검사한 뒤 저장하고 있습니다.');
      await api('/api/connection', 'PUT', settings());
      el('conn-password').value = ''; el('conn-sudoPassword').value = ''; invalidateKey();
      el('connection-badge').textContent = '저장된 연결 사용 중';
      status('연결을 저장했습니다. 아래에서 프로젝트를 생성하세요. 설정 변경 시 비밀번호와 서버 키 확인을 다시 입력하세요.');
    });
  });
  api('/api/connection', 'GET').then(data => {
    fields.filter(key => !key.toLowerCase().includes('password')).forEach(key => {
      if (key === 'sudoEnabled') el('conn-' + key).checked = data[key];
      else el('conn-' + key).value = data[key] ?? '';
    });
    el('sudo-fields').hidden = !data.sudoEnabled;
    el('connection-badge').textContent = data.saved ? '저장된 연결 사용 중' : '첫 연결 설정 / 기존 파일 설정 사용';
    el('connection-panel').open = !data.saved;
    if (data.loadFailed) status('저장한 설정 파일을 읽을 수 없습니다. 연결을 다시 설정하세요.', true);
  }).catch(error => status(error.message, true));
  api('/api/application', 'GET').then(data => {
    el('settings-location').textContent = data.connectionFile;
    el('exit-app').hidden = !data.packaged;
  }).catch(() => {});
  el('exit-app').addEventListener('click', async () => {
    if (!window.confirm('플랫폼을 종료할까요? 연결 설정은 유지됩니다.')) return;
    el('exit-app').disabled = true;
    try {
      const response = await fetch('/api/application/exits', {method: 'POST', headers: {'Content-Type': 'application/json'}, body: '{}'});
      if (!response.ok) throw new Error('종료하지 못했습니다.');
      document.querySelectorAll('button, input').forEach(control => control.disabled = true);
      status('종료 요청을 보냈습니다. 이 탭을 닫아도 됩니다.');
    } catch (error) { status(error.message, true); el('exit-app').disabled = false; }
  });
})();
