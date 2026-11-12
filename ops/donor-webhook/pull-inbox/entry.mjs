import enqueue from './enqueue.sql';
import claim from './claim.sql';
import ack from './ack.sql';
import { createWorker } from './worker.mjs';
export default createWorker({ enqueue, claim, ack });
