import express, { type Express, Router } from 'express';

import type { Server as HttpServer } from 'http';
import { Server as IoServer } from 'socket.io';

const COURSE_WS_URL = 'wss://8.229.22.124'

// TODO: Change this
const DEVELOPER_NAME = {
  firstName: 'TODO',
  lastName: 'TEST',
};

// TODO: add ec2 server public ip
const SERVER_PUBLIC_IP = '';

// Helper function to format number as a two-digit string
// Used exclusively in formatGmtTime
const pad = (n: number): string => String(n).padStart(2, '0');

// Returns formatted local server time
export function formatGmtTime(date: Date = new Date()): string {
  // Get offset relative to GMT and find the correct sign
  const offsetMin = -date.getTimezoneOffset();
  const sign = offsetMin >= 0 ? '+' : '-';
  const abs = Math.abs(offsetMin)

  return (
    `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())} ` +
    `GMT${sign}${pad(Math.floor(abs / 60))}:${pad(abs % 60)}`
  )
}

// API routes
const apiRouter = Router();

apiRouter.get('/server-ip', (_req, res) => {
  res.json({ ip: SERVER_PUBLIC_IP });
})

apiRouter.get('/server-time', (_req, res) => {
  res.json({ time: formatGmtTime() });
})

apiRouter.get('/developer-name', (_req, res) => {
  res.json(DEVELOPER_NAME);
})


export function createApp(): Express {
  const app = express();

  app.get('/health', (_req, res) => {
    res.json({ status: 'ok' });
  });

  app.use('/api', apiRouter);

  app.use((_req, res) => {
    res.status(404).json({ error: 'Not Found' });
  });

  return app;
}

// Live pixel pass through (course WS -> Socket.io server -> Android app)
// Each connected app gets its own upstream connection, so its picture starts blank
export function attachLiveRelay(server: HttpServer): IoServer {
  const io = new IoServer(server);
 
  io.on('connection', (client) => {
    const upstream = new WebSocket(COURSE_WS_URL); // Node 22 global WebSocket
 
    // Relay every pixel immediately, payload untouched
    upstream.addEventListener('message', (event) => {
      client.emit('pixel', event.data);
    });
    upstream.addEventListener('error', () => console.error('Course WebSocket error'));
 
    client.on('disconnect', () => upstream.close());
  });
 
  return io;
}
