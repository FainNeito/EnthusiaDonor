import enqueue from './enqueue.sql';
import claim from './claim.sql';
import ack from './ack.sql';
import quarantine from './quarantine.sql';
import prune from './prune.sql';
import status from './status.sql';
import { createWorker } from './worker.mjs';
export default createWorker({ enqueue, claim, ack, quarantine, prune, status });
