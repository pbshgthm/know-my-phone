import { Router } from "express";
import { join } from "path";
import {
  listClients,
  listConversations,
  getConversation,
  deleteConversation,
  getDataDir,
} from "./dataStore.js";

const router = Router();

// List all client IDs
router.get("/api/clients", async (_req, res) => {
  const clients = await listClients();
  res.json(clients);
});

// List conversations for a client
router.get("/api/clients/:clientId/conversations", async (req, res) => {
  const convs = await listConversations(req.params.clientId);
  res.json(convs);
});

// Get full conversation data
router.get(
  "/api/clients/:clientId/conversations/:convId",
  async (req, res) => {
    const conv = await getConversation(
      req.params.clientId,
      req.params.convId
    );
    if (!conv) {
      res.status(404).json({ error: "Conversation not found" });
      return;
    }
    res.json(conv);
  }
);

// Delete a conversation
router.delete(
  "/api/clients/:clientId/conversations/:convId",
  async (req, res) => {
    const ok = await deleteConversation(
      req.params.clientId,
      req.params.convId
    );
    if (!ok) {
      res.status(404).json({ error: "Conversation not found" });
      return;
    }
    res.json({ ok: true });
  }
);

// Serve binary files (screenshots, audio, ui-trees)
// Route: /api/data/:clientId/:convId/:type/:filename
router.get("/api/data/:clientId/:convId/:type/:filename", (req, res) => {
  const { clientId, convId, type, filename } = req.params;
  const filePath = `${type}/${filename}`;

  // Security: prevent path traversal
  if (filePath.includes("..")) {
    res.status(400).json({ error: "Invalid path" });
    return;
  }

  const fullPath = join(getDataDir(), clientId, convId, filePath);

  // Set content type based on extension
  if (filePath.endsWith(".wav")) {
    res.type("audio/wav");
  } else if (filePath.endsWith(".mp3")) {
    res.type("audio/mpeg");
  } else if (filePath.endsWith(".jpg") || filePath.endsWith(".jpeg")) {
    res.type("image/jpeg");
  } else if (filePath.endsWith(".png")) {
    res.type("image/png");
  } else if (filePath.endsWith(".json")) {
    res.type("application/json");
  }

  res.sendFile(fullPath, (err) => {
    if (err) {
      res.status(404).json({ error: "File not found" });
    }
  });
});

export default router;
