import type { Server } from 'node:http';
export function startProvider(port?: number): Promise<Server>;
