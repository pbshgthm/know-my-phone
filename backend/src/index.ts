import "dotenv/config";
import express from "express";
import { join } from "path";
import { createServer } from "http";
import { WebSocketServer } from "ws";
import { handleConnection, getAllClients } from "./wsHandler.js";
import apiRoutes from "./apiRoutes.js";

const PORT = parseInt(process.env.PORT || "8765", 10);

const app = express();
app.use(express.json());

// Serve web UI static files
app.use(express.static(join(process.cwd(), "public")));

// API routes for conversation viewer
app.use(apiRoutes);

// Health check endpoint
app.get("/health", (_req, res) => {
  res.json({
    status: "ok",
    timestamp: new Date().toISOString(),
    uptime: process.uptime(),
  });
});

// Create HTTP server and attach WebSocket
const server = createServer(app);

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
  console.log(`🚀 Know My Phone Backend Server`);
  console.log('='.repeat(60));
  console.log(`✅ Server running on port ${PORT}`);
  console.log(`🌐 Web UI:    http://localhost:${PORT}/`);
  console.log(`🌐 Health:    http://localhost:${PORT}/health`);
  console.log(`🔌 WebSocket: ws://localhost:${PORT}`);
  console.log('='.repeat(60));
  console.log(`📋 Environment:`);
  console.log(`   ANTHROPIC_API_KEY: ${process.env.ANTHROPIC_API_KEY ? '✓ Set' : '✗ Missing'}`);
  console.log(`   GOOGLE_GENERATIVE_AI_API_KEY: ${process.env.GOOGLE_GENERATIVE_AI_API_KEY ? '✓ Set' : '✗ Missing'}`);
  console.log(`   LLM_TEXT_MODEL: ${process.env.LLM_TEXT_MODEL || 'anthropic/claude-4.5-haiku (default)'}`);
  console.log(`   LLM_VISION_MODEL: ${process.env.LLM_VISION_MODEL || 'anthropic/claude-4.5-haiku (default)'}`);
  console.log(`   ELEVENLABS_API_KEY: ${process.env.ELEVENLABS_API_KEY ? '✓ Set' : '✗ Missing'}`);
  console.log(`   ELEVENLABS_STT_MODEL_ID: ${process.env.ELEVENLABS_STT_MODEL_ID || 'scribe_v2 (default)'}`);
  console.log('='.repeat(60));
  console.log(`⏳ Waiting for connections...\n`);
});

// Graceful shutdown
function gracefulShutdown(signal: string) {
  console.log(`\n[Server] Received ${signal}, shutting down gracefully...`);

  // Close all WebSocket connections with "going away" code
  for (const [ws] of getAllClients()) {
    ws.close(1001, "Server shutting down");
  }

  wss.close(() => {
    console.log("[Server] WebSocket server closed");
    server.close(() => {
      console.log("[Server] HTTP server closed");
      process.exit(0);
    });
  });

  // Force exit after 5 seconds
  setTimeout(() => {
    console.error("[Server] Forced shutdown after timeout");
    process.exit(1);
  }, 5000);
}

process.on("SIGTERM", () => gracefulShutdown("SIGTERM"));
process.on("SIGINT", () => gracefulShutdown("SIGINT"));
