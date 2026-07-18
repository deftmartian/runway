import { randomBytes } from 'node:crypto';

// The prefix makes the production contract explicit. The encoded payload is
// exactly 32 bytes from Node's operating-system CSPRNG.
process.stdout.write(`runway-secret-v1_${randomBytes(32).toString('base64url')}\n`);
