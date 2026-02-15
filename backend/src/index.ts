import "dotenv/config";
import express from "express";
import { createServer } from "http";
import { WebSocketServer } from "ws";
import { handleConnection } from "./wsHandler.js";

const PORT = parseInt(process.env.PORT || "8765", 10);

const app = express();
app.use(express.json());

// Health check endpoint
app.get("/health", (_req, res) => {
  res.json({
    status: "ok",
    timestamp: new Date().toISOString(),
    uptime: process.uptime(),
  });
});

// Debug: log all incoming requests
app.use((req, res, next) => {
  console.log(`[Express] 📥 ${req.method} ${req.url} from ${req.ip}`);
  console.log(`[Express] 🔍 Headers:`, JSON.stringify(req.headers, null, 2));
  next();
});

// Create HTTP server and attach WebSocket
const server = createServer(app);

// Debug: Log ALL incoming connections at raw HTTP level
server.on("request", (req, res) => {
  console.log(`[HTTP] 🌐 ${req.method} ${req.url} from ${req.socket.remoteAddress}`);
  console.log(`[HTTP] 🔍 Connection: ${req.headers.connection}`);
  console.log(`[HTTP] 🔍 Upgrade: ${req.headers.upgrade}`);
});

const wss = new WebSocketServer({ noServer: true });

// Handle WebSocket upgrade requests
server.on("upgrade", (request, socket, head) => {
  console.log(`[Server] 🔄 WebSocket upgrade request received`);
  console.log(`[Server] 🔍 URL: ${request.url}`);
  console.log(`[Server] 🔍 From: ${request.socket.remoteAddress}`);
  console.log(`[Server] 🔍 Headers:`, JSON.stringify(request.headers, null, 2));

  wss.handleUpgrade(request, socket, head, (ws) => {
    console.log(`[Server] ✅ WebSocket upgrade successful`);
    wss.emit("connection", ws, request);
  });
});

wss.on("connection", (ws) => {
  handleConnection(ws);
});

server.listen(PORT, () => {
  console.log('\n' + '='.repeat(60));
  console.log(`🚀 Know Your Phone Backend Server`);
  console.log('='.repeat(60));
  console.log(`✅ Server running on port ${PORT}`);
  console.log(`🌐 HTTP:      http://localhost:${PORT}/health`);
  console.log(`🔌 WebSocket: ws://localhost:${PORT}`);
  console.log('='.repeat(60));
  console.log(`📋 Environment:`);
  console.log(`   OPENAI_API_KEY:     ${process.env.OPENAI_API_KEY ? '✓ Set' : '✗ Missing'}`);
  console.log(`   OPENROUTER_API_KEY: ${process.env.OPENROUTER_API_KEY ? '✓ Set' : '✗ Missing'}`);
  console.log(`   ELEVENLABS_API_KEY: ${process.env.ELEVENLABS_API_KEY ? '✓ Set' : '✗ Missing'}`);
  console.log('='.repeat(60));
  console.log(`⏳ Waiting for connections...\n`);
});
