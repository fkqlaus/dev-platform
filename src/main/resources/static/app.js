const form = document.querySelector('#project-form');
const statusText = document.querySelector('#status');
const result = document.querySelector('#result');
const button = document.querySelector('#create');

form.addEventListener('submit', async (event) => {
    event.preventDefault();
    if (button.disabled || !form.reportValidity()) return;
    button.disabled = true;
    result.hidden = true;
    statusText.className = '';
    statusText.textContent = '서버에 저장소를 만들고 확인하고 있습니다…';
    try {
        const response = await fetch('/api/projects', {
            method: 'POST', headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({name: form.elements.name.value})
        });
        const body = await response.json();
        if (!response.ok) throw new Error(body.detail || '요청을 처리하지 못했습니다.');
        document.querySelector('#result-title').textContent = body.name + '.git';
        document.querySelector('#clone-url').value = body.cloneUrl;
        document.querySelector('#clone-command').textContent = 'git clone ' + body.cloneUrl;
        document.querySelector('#remote-command').textContent = 'git remote add origin ' + body.cloneUrl;
        document.querySelector('#alternate-command').textContent = 'git remote add company ' + body.cloneUrl;
        document.querySelector('#copy-url').textContent = '주소 복사';
        result.hidden = false;
        statusText.textContent = '저장소가 생성되었습니다. 아래에서 연결 방법을 확인하세요.';
        result.scrollIntoView({behavior: 'smooth', block: 'start'});
    } catch (error) {
        statusText.className = 'error';
        statusText.textContent = error instanceof TypeError
            ? '응답을 받지 못했습니다. 재시도 전에 서버의 저장소 생성 여부를 확인하세요.' : error.message;
    } finally {
        button.disabled = false;
    }
});
document.querySelector('#copy-url').addEventListener('click', async (event) => {
    try {
        await navigator.clipboard.writeText(document.querySelector('#clone-url').value);
        event.target.textContent = '복사 완료';
    } catch {
        document.querySelector('#clone-url').select();
        event.target.textContent = '선택된 주소를 복사하세요';
    }
});
