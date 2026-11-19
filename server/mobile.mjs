import fs from 'node:fs/promises';
import path from 'node:path';
import { randomUUID } from 'node:crypto';

const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
async function readJson(file) { return JSON.parse(await fs.readFile(file, 'utf8')); }
async function writeJson(file, value) {
  await fs.mkdir(path.dirname(file), { recursive: true });
  const temp = `${file}.${randomUUID()}.tmp`;
  await fs.writeFile(temp, JSON.stringify(value));
  await fs.rename(temp, file);
}
export async function createMobile(config, storage, options = {}) {
  const fetcher = options.fetch ?? fetch;
  const folder = path.join(storage, 'mobile-jobs');
  await fs.mkdir(folder, { recursive: true });
  // A process restart cannot safely replay a model request or file-changing task.
  for (const name of await fs.readdir(folder)) {
    if (!/^[0-9a-f-]{36}\.json$/i.test(name)) continue;
    const file = path.join(folder, name);
    const job = await readJson(file);
    if (job.state === 'running' && job.mode === 'chat') {
      await writeJson(file, { ...job, state: 'failed', error: 'Мост перезапущен. Отправьте сообщение ещё раз.' });
    }
  }
  let accepting = false, chatActive = false;
  const fileFor = id => {
    if (!uuid.test(id)) throw Error('Некорректный идентификатор');
    return path.join(folder, `${id}.json`);
  };
  function publicJob(job) {
    const { request, ...result } = job;
    return result;
  }
  async function refresh(job) {
    if (!job.taskId || job.state !== 'running') return job;
    try {
      const report = await readJson(path.join(storage, 'tasks', job.taskId, 'result.json'));
      if (report.taskId !== job.taskId || report.workspaceId !== job.workspaceId) throw Error('Report route mismatch');
      const lines = [report.summary, '', ...(report.tests ?? []).map(x => `Проверка: ${x}`), ...(report.blockers ?? []).map(x => `Проблема: ${x}`), ...(report.changedFiles ?? []).map(x => `Изменён файл: ${x.path}`)];
      job = { ...job, state: report.status === 'failed' ? 'failed' : 'completed', taskStatus: report.status, answer: lines.join('\n').trim(), completedAt: report.completedAt };
      await writeJson(fileFor(job.id), job);
    } catch (error) { if (error.code !== 'ENOENT') throw error; }
    return job;
  }
  function validate(input) {
    if (!uuid.test(input.requestId ?? '') || !uuid.test(input.chatId ?? '')) throw Error('Требуется идентификатор сообщения и чата');
    if (typeof input.text !== 'string' || !input.text.trim() || input.text.length > 8000) throw Error('Сообщение: от 1 до 8000 символов');
    if (!['chat', 'task'].includes(input.mode ?? 'chat')) throw Error('Неизвестный режим');
    if (input.workspaceId && !Object.hasOwn(config.workspaces, input.workspaceId)) throw Error('Проект не зарегистрирован');
    if (!Array.isArray(input.history ?? []) || (input.history ?? []).length > 20) throw Error('Слишком длинная история');
    let total = input.text.length;
    for (const message of input.history ?? []) {
      if (!['user', 'assistant'].includes(message.role) || typeof message.content !== 'string' || message.content.length > 8000) throw Error('Некорректная история');
      total += message.content.length;
    }
    if (total > 18000) throw Error('История слишком велика. Начните новый чат.');
    return { ...input, text: input.text.trim(), mode: input.mode ?? 'chat' };
  }
  async function chat(job, input) {
    try {
      const modelBase = config.modelBaseUrl.replace(/\/$/, '');
      if (config.loadProfiles?.[config.model]) {
        const inventoryResponse = await fetcher(`${modelBase}/api/v1/models`, { redirect: 'error', signal: AbortSignal.timeout(10000) });
        if (!inventoryResponse.ok) throw Error('Не удалось проверить локальную модель');
        const model = (await inventoryResponse.json()).models?.find(item => item.key === config.model);
        if (!model) throw Error('Локальная модель не установлена');
        if (!model.loaded_instances?.length) {
          const loaded = await fetcher(`${modelBase}/api/v1/models/load`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, redirect: 'error', signal: AbortSignal.timeout(180000), body: JSON.stringify({ model: config.model, ...config.loadProfiles[config.model] }) });
          if (!loaded.ok) throw Error('Не удалось загрузить модель. Проверьте Bionic на ПК.');
        }
      }
      let project = '';
      if (input.workspaceId) {
        const workspace = config.workspaces[input.workspaceId];
        project = `\nSelected project: ${input.workspaceId}. Worker path: ${workspace.path}. This chat has no file or shell tools; do not claim current source inspection or code edits.`;
      }
      const messages = [{ role: 'system', content: `Ты локальный помощник Bionic. Отвечай по-русски, ясно и кратко. В этом режиме ты общаешься, а не выполняешь команды. Не выдумывай выполненные действия. Для работы с файлами пользователь выбирает режим «Задача».${project}` }, ...(input.history ?? []), { role: 'user', content: input.text }];
      const response = await fetcher(`${modelBase}/v1/chat/completions`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, redirect: 'error',
        body: JSON.stringify({ model: config.model, messages, stream: false, store: false, temperature: 0.3, max_tokens: 2048, chat_template_kwargs: { enable_thinking: false } }),
        signal: AbortSignal.timeout(180000)
      });
      if (!response.ok) throw Error(`Модель недоступна (HTTP ${response.status}). Проверьте Bionic на ПК.`);
      const answer = (await response.json()).choices?.[0]?.message?.content;
      if (typeof answer !== 'string' || !answer.trim()) throw Error('Модель вернула пустой ответ');
      await writeJson(fileFor(job.id), { ...job, state: 'completed', answer, completedAt: new Date().toISOString() });
    } catch (error) {
      await writeJson(fileFor(job.id), { ...job, state: 'failed', error: error.name === 'TimeoutError' ? 'Модель не ответила за 3 минуты.' : error.message });
    } finally { chatActive = false; }
  }
  return {
    isBusy: () => chatActive || accepting,
    isChatBusy: () => chatActive,
    async handle(req, data, reply, workerBusy) {
      const parsed = new URL(req.url, 'http://localhost');
      if (!parsed.pathname.startsWith('/mobile/')) return false;
      if (req.method === 'GET' && parsed.pathname === '/mobile/config') {
        reply(200, { apiVersion: 1, model: config.model, projects: Object.keys(config.workspaces).map(id => ({ id, name: id === 'yago' ? 'YaGo' : id })), chatBusy: chatActive, workerBusy });
        return true;
      }
      const match = parsed.pathname.match(/^\/mobile\/jobs\/([0-9a-f-]{36})$/i);
      if (req.method === 'GET' && match) {
        try { reply(200, publicJob(await refresh(await readJson(fileFor(match[1]))))); }
        catch (error) { if (error.code === 'ENOENT') reply(404, { error: 'Сообщение не найдено' }); else throw error; }
        return true;
      }
      if (req.method !== 'POST' || parsed.pathname !== '/mobile/messages') { reply(404, { error: 'Неизвестный мобильный маршрут' }); return true; }
      const input = validate(JSON.parse(data));
      try {
        const old = await readJson(fileFor(input.requestId));
        if (JSON.stringify(old.request) !== JSON.stringify(input)) { reply(409, { error: 'Идентификатор уже использован для другого сообщения' }); return true; }
        reply(200, publicJob(await refresh(old))); return true;
      } catch (error) { if (error.code !== 'ENOENT') throw error; }
      if (accepting || chatActive || workerBusy) { reply(409, { error: 'Помощник занят. Дождитесь ответа или повторите позже.' }); return true; }
      accepting = true;
      let job = { id: input.requestId, chatId: input.chatId, mode: input.mode, workspaceId: input.workspaceId ?? '', state: 'running', createdAt: new Date().toISOString(), request: input };
      try {
        if (input.mode === 'task') {
          const workspace = config.workspaces[input.workspaceId];
          if (!workspace) throw Error('Для задачи выберите проект');
          const files = input.files;
          if (!Array.isArray(files) || !files.length || files.length > 30 || files.some(x => typeof x !== 'string' || !x.trim())) throw Error('Укажите от 1 до 30 файлов задачи');
          if (typeof input.readOnly !== 'boolean') throw Error('Укажите режим доступа к файлам');
          const route = Object.entries(config.routes).find(([, route]) => route.workspaceIds.includes(input.workspaceId));
          if (!route) throw Error('Проект не имеет зарегистрированного чата проверки');
          const task = { workspaceId: input.workspaceId, parentThreadId: route[0], hostId: route[1].hostId, title: `Телефон: ${input.text.slice(0, 150)}`, prompt: `Scope: ${input.readOnly ? 'Read-only investigation; do not change any files.' : 'Implement the user request only in the authorized worker files.'}\n${input.text}\nReturn a concise Russian file report with evidence. Shell through RTK is required. No commit/push/deploy or main integration.`, acceptance: 'Report actual findings, edits, checks and blockers. Preserve task scope and local changes.', readPaths: files, writePaths: input.readOnly ? [] : files, skills: workspace.skills };
          const response = await fetcher(`http://127.0.0.1:${config.port}/tasks`, { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${config.token}` }, body: JSON.stringify(task), signal: AbortSignal.timeout(10000) });
          const result = await response.json();
          if (!response.ok) { reply(response.status, { error: result.error ?? 'Не удалось принять задачу' }); return true; }
          job.taskId = result.taskId;
        } else { chatActive = true; }
        await writeJson(fileFor(job.id), job);
        reply(202, publicJob(job));
        if (input.mode === 'chat') void chat(job, input);
        return true;
      } catch (error) {
        if (input.mode === 'chat') chatActive = false;
        throw error;
      } finally { accepting = false; }
    }
  };
}
