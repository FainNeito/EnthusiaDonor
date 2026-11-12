// Pure, dry-run routing decisions only: no I/O, scheduler, recipient or transport.
const rank = code => code === 0 ? 0 : code === 1 ? 1 : 2;
const integer = n => Number.isSafeInteger(n) && n >= 0;
const exactKeys = (value, keys) => value && typeof value === 'object' && !Array.isArray(value)
  && Object.keys(value).sort().join(',') === keys.sort().join(',');
const stateKeys = ['version', 'key', 'started_at', 'last_tick', 'last_result_at', 'bad_streak', 'worst_code', 'ok_streak', 'serial', 'incident'];
const incidentKeys = ['id', 'opened_at', 'severity', 'last_notice', 'escalated'];

function validate(state, key, now) {
  if (!exactKeys(state, stateKeys) || state.version !== 1 || state.key !== key
      || !['started_at', 'last_tick', 'bad_streak', 'worst_code', 'ok_streak', 'serial'].every(k => integer(state[k]))
      || state.worst_code > 3 || state.bad_streak > 3 || state.ok_streak > 3
      || state.last_tick > now || state.started_at > state.last_tick
      || (state.last_result_at !== null && (!integer(state.last_result_at) || state.last_result_at > state.last_tick)))
    throw Error('Invalid routing state or clock');
  const incident = state.incident;
  if (incident !== null && (!exactKeys(incident, incidentKeys) || typeof incident.id !== 'string'
      || incident.id !== `${key}:${state.serial}:${incident.opened_at}`
      || !integer(incident.opened_at) || !integer(incident.last_notice)
      || incident.opened_at > incident.last_notice || incident.last_notice > state.last_tick
      || ![1, 2, 3].includes(incident.severity) || typeof incident.escalated !== 'boolean'))
    throw Error('Invalid incident state');
}

/** checked_at is the stable supervisor observation time, retained across retries.
 * Omit only for a new synchronous observation. null means a watchdog tick.
 * The host must atomically persist state and intents; this function sends nothing.
 */
export function route(previous, sample, now, key = 'donor-inbox') {
  if (!integer(now) || !/^[a-z0-9_.-]{1,64}$/.test(key)) throw Error('Invalid routing input');
  if (previous !== null) validate(previous, key, now);
  const state = previous === null ? { version: 1, key, started_at: now, last_tick: now,
    last_result_at: null, bad_streak: 0, worst_code: 0, ok_streak: 0, serial: 0, incident: null }
    : structuredClone(previous);
  const intents = [];
  const emit = (type, reason, roles) => {
    intents.push({ dry_run: true, type, reason, roles, incident: state.incident.id,
      dedup_key: `${state.incident.id}:${type}:${now}`, severity: state.incident.severity, at: now });
    state.incident.last_notice = now;
  };
  let fresh = false;
  let code;
  if (sample !== null) {
    const checked = sample?.checked_at ?? now;
    if (!integer(checked) || checked > now) throw Error('Invalid observation time');
    fresh = (state.last_result_at === null || checked > state.last_result_at) && now - checked <= 120;
    if (fresh) {
      state.last_result_at = checked;
      code = [0, 1, 2, 3].includes(sample?.code) ? sample.code : 3;
      if (code === 0) {
        state.bad_streak = 0; state.worst_code = 0; state.ok_streak = Math.min(3, state.ok_streak + 1);
      } else {
        state.ok_streak = 0; state.bad_streak = Math.min(3, state.bad_streak + 1);
        if (rank(code) >= rank(state.worst_code)) state.worst_code = code;
      }
    }
  }
  const missing = now - (state.last_result_at ?? state.started_at) >= 900;
  if (missing) state.ok_streak = 0;
  if (state.incident && fresh && code === 0 && state.ok_streak >= 3) {
    emit('RECOVERED', 'THREE_OK_RESULTS', state.incident.escalated ? ['primary', 'backup'] : ['primary']);
    state.incident = null;
  } else {
    const severity = missing ? 3 : state.worst_code;
    const trigger = missing || (fresh && code !== 0 && state.bad_streak >= (severity === 1 ? 3 : 2));
    const reason = missing ? 'MISSING_RESULTS' : 'PERSISTENT_CHECK_FAILURE';
    if (!state.incident && trigger) {
      if (!Number.isSafeInteger(state.serial + 1)) throw Error('Incident sequence exhausted');
      state.serial++;
      state.incident = { id: `${key}:${state.serial}:${now}`, opened_at: now, severity,
        last_notice: now, escalated: false };
      emit('OPEN', reason, ['primary']);
    } else if (state.incident) {
      if (trigger && rank(severity) > rank(state.incident.severity)) {
        state.incident.severity = severity;
        emit('WORSENED', reason, state.incident.escalated ? ['primary', 'backup'] : ['primary']);
      }
      if (!state.incident.escalated && now - state.incident.opened_at >= 900) {
        state.incident.escalated = true; emit('ESCALATE', 'UNRESOLVED_15_MINUTES', ['backup']);
      } else if (now - state.incident.last_notice >= 3600) {
        emit('REMINDER', missing ? 'MISSING_RESULTS' : 'UNRESOLVED_INCIDENT', state.incident.escalated ? ['primary', 'backup'] : ['primary']);
      }
    }
  }
  state.last_tick = now;
  return { state, intents };
}
