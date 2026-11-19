// Two running jobs share one FIFO inference channel on the loaded parallel=1 model.
export function createJobSlots(capacity = 2, onAvailable = () => {}) {
  if (!Number.isInteger(capacity) || capacity < 1 || capacity > 2) throw Error('Job slots must be 1 or 2');
  const running = new Set();
  return {
    capacity,
    get active() { return running.size; },
    acquire(label) {
      if (running.size >= capacity) return null;
      const key = Symbol(label);
      running.add(key);
      let released = false;
      return () => { if (!released) { released = true; running.delete(key); onAvailable(); } };
    }
  };
}

export function serializeModelFetch(fetcher = fetch, modelBaseUrl) {
  let tail = Promise.resolve();
  return async (url, options = {}) => {
    const target = new URL(url);
    // Inventory is metadata, not inference; it must stay available during a generation.
    if (target.origin !== new URL(modelBaseUrl).origin || (target.pathname === '/api/v1/models' && (options.method ?? 'GET') === 'GET')) return fetcher(url, options);
    const previous = tail;
    let release;
    tail = new Promise(r => { release = r; });
    let entered = false;
    try {
      await waitForTurn(previous, options.signal);
      entered = true;
      options.signal?.throwIfAborted();
      const response = await fetcher(url, options);
      // Hold the inference lane until the entire non-streaming body is received.
      const data = await response.json();
      return { ok: response.ok, status: response.status, json: async () => data };
    } finally {
      if (entered) release();
      else void previous.then(release); // cancelled waiters cannot open the lane early
    }
  };
}

function waitForTurn(previous, signal) {
  if (!signal) return previous;
  signal.throwIfAborted();
  return new Promise((resolve, reject) => {
    const abort = () => { signal.removeEventListener('abort', abort); reject(signal.reason); };
    signal.addEventListener('abort', abort, { once: true });
    previous.then(() => { signal.removeEventListener('abort', abort); resolve(); });
  });
}

// Admission serializes durable metadata only, never the execution of two jobs.
export function createSubmissionGate(maxWaiting = 20) {
  let locked = false;
  const waiting = [];
  function releaseHandle() {
    let done = false;
    return () => {
      if (done) return;
      done = true;
      const next = waiting.shift();
      if (next) next(releaseHandle());
      else locked = false;
    };
  }
  return {
    get busy() { return locked || waiting.length > 0; },
    acquire() {
      if (!locked) { locked = true; return Promise.resolve(releaseHandle()); }
      if (waiting.length >= maxWaiting) return Promise.resolve(null);
      return new Promise(resolve => waiting.push(resolve));
    }
  };
}
