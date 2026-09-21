import express, { type Express, Router } from 'express';

import type { Server as HttpServer } from 'http';
import { Server as IoServer } from 'socket.io';

const COURSE_WS_URL = 'wss://8.229.22.124'

const DEVELOPER_NAME = {
  firstName: 'Wit',
  lastName: 'Lin',
};

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

export async function getServerPublicIp(): Promise<string> {
  const response = await fetch("https://api.ipify.org?format=json");

  if (!response.ok) {
    throw new Error(`Failed to get public IP: ${response.status}`);
  }

  const data = await response.json() as { ip: string };
  
  return data.ip;
}

// Stock surprise (button 3 functionality)
const STOCK_SYMBOL = 'AAPL';
const INVESTMENT = 1_000_000;
const MARKET_OPEN_MS = (9 * 60 + 30) * 60_000; // 9:30 ET, as ms after midnight
const DAY_MS = 86_400_000;
 
// "t" is New York wall-clock time read as if it were UTC (StockData labels a 9:30 ET open as
// "T09:30:00.000Z"), so it can be compared directly with the open time computed below.
type Bar = { t: number; open: number };
type RawBar = { date: string; data: { open: number; is_extended_hours?: boolean } };
type TimerResult = { basis: string; symbol: string; buy: number; sell: number; gain: number; percent: number };
 
// YYYY-MM-DD of the Monday to buy on, based on the New York date the timer started
function buyDay(startMs: number): string {
  const today = new Date(startMs).toLocaleDateString('en-CA', { timeZone: 'America/New_York' });
  const d = Date.parse(`${today}T00:00:00Z`);
  const sinceMonday = (new Date(d).getUTCDay() + 6) % 7; // Mon = 0 ... Sun = 6
  return new Date(d - (sinceMonday || 7) * DAY_MS).toISOString().slice(0, 10);
}
 
async function fetchBars(day: string): Promise<Bar[]> {
  const token = process.env.STOCK_DATA_API_KEY;
  if (!token) throw new Error('STOCK_DATA_API_KEY is not set');
 
  const params = new URLSearchParams({
    symbols: STOCK_SYMBOL,
    api_token: token,
    interval: 'minute',
    sort: 'asc',
    date: day,
  });
  const res = await fetch(`https://api.stockdata.org/v1/data/intraday?${params}`);
  if (!res.ok) throw new Error(`StockData request failed (HTTP ${res.status})`);
 
  const body = (await res.json()) as { data?: RawBar[] };
  return (Array.isArray(body.data) ? body.data : [])
    .filter((r) => !r.data.is_extended_hours)
    .map((r) => ({ t: Date.parse(r.date), open: r.data.open }));
}
 
// Price at time t = open of the last bar at or before t (first bar if none yet).
// Past the last bar of the day this resolves to the end of the session.
// Precondition: Requirres bars to not be empty
function priceAt(bars: Bar[], t: number): number {
  let last = bars[0]!;
  for (const b of bars) {
    if (b.t <= t) last = b;
    else break;
  }
  return last.open;
}
 
const round2 = (n: number): number => Math.round(n * 100) / 100;
 
// startMs / endMs: epoch milliseconds recorded by the app when the timer started / ended
async function computeTimerResult(startMs: number, endMs: number): Promise<TimerResult> {
  const day = buyDay(startMs);
  const bars = await fetchBars(day);
  if (bars.length === 0) throw new Error(`No market data for ${day} (market holiday?)`);
 
  const openT = Date.parse(`${day}T00:00:00Z`) + MARKET_OPEN_MS;
  const buy = priceAt(bars, openT);
  const sell = priceAt(bars, openT + (endMs - startMs));
  const gain = (INVESTMENT / buy) * sell - INVESTMENT;
 
  return {
    basis: `Replayed from the open on ${day} (9:30 ET) for the same duration.`,
    symbol: STOCK_SYMBOL,
    buy,
    sell,
    gain: round2(gain),
    percent: round2((gain / INVESTMENT) * 100),
  };
}


// API routes
const apiRouter = Router();

apiRouter.get('/server-ip', async (_req, res) => {
  const ip = await getServerPublicIp()
  res.json({ ip });
})

apiRouter.get('/server-time', (_req, res) => {
  res.json({ time: formatGmtTime() });
})

apiRouter.get('/developer-name', (_req, res) => {
  res.json(DEVELOPER_NAME);
})

// Timer surprise: start/end are epoch milliseconds recorded by the app
apiRouter.get('/timer-result', async (req, res) => {
  const start = Number(req.query.start);
  const end = Number(req.query.end);
  if (!Number.isFinite(start) || !Number.isFinite(end) || end <= start) {
    res.status(400).json({ error: 'start and end must be epoch milliseconds, with end after start' });
    return;
  }
 
  try {
    res.json(await computeTimerResult(start, end));
  } catch (err) {
    console.error(err);
    res.status(502).json({ error: err instanceof Error ? err.message : 'Stock lookup failed' });
  }
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
