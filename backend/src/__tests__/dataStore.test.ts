import { describe, it, expect, beforeEach, afterEach, jest } from "@jest/globals";
import { mkdtemp, mkdir, readFile, rm, writeFile } from "fs/promises";
import { join } from "path";
import { tmpdir } from "os";

interface DataStoreModule {
  initConversation: (
    clientId: string,
    sessionId: string,
    language: string,
    deviceInfo: { manufacturer: string; model: string; androidVersion: string }
  ) => Promise<unknown>;
  startTurn: (
    clientId: string,
    sessionId: string,
    opts?: { autoScreenshot?: boolean; audioReceivedAt?: string }
  ) => Promise<number>;
  updateTurn: (
    clientId: string,
    sessionId: string,
    turnId: number,
    updates: Record<string, unknown>
  ) => Promise<void>;
  getConversation: (clientId: string, sessionId: string) => Promise<{
    turns: Array<{ turnId: number; input: { transcript?: string } }>;
  } | null>;
  listConversations: (clientId: string) => Promise<Array<{ id: string; turnCount: number }>>;
}

const DEVICE = {
  manufacturer: "test",
  model: "model",
  androidVersion: "14",
};

let dataDir = "";

async function loadDataStore(): Promise<DataStoreModule> {
  jest.resetModules();
  process.env.DATA_DIR = dataDir;
  return (await import("../dataStore.js")) as unknown as DataStoreModule;
}

describe("dataStore turn/timing integrity", () => {
  beforeEach(async () => {
    dataDir = await mkdtemp(join(tmpdir(), "kyp-datastore-"));
  });

  afterEach(async () => {
    delete process.env.DATA_DIR;
    if (dataDir) {
      await rm(dataDir, { recursive: true, force: true });
    }
  });

  it("allocates monotonically increasing turn IDs from persisted conversation state", async () => {
    const ds1 = await loadDataStore();
    await ds1.initConversation("c1", "s1", "en", DEVICE);
    const t1 = await ds1.startTurn("c1", "s1");
    const t2 = await ds1.startTurn("c1", "s1");
    expect(t1).toBe(1);
    expect(t2).toBe(2);

    // Simulate process restart (fresh module import, in-memory counters reset).
    const ds2 = await loadDataStore();
    const t3 = await ds2.startTurn("c1", "s1");
    expect(t3).toBe(3);
  });

  it("updates the latest duplicate turn entry and dedupes on reads", async () => {
    const ds = await loadDataStore();
    const convDir = join(dataDir, "c1", "s1");
    await mkdir(convDir, { recursive: true });
    const conversation = {
      clientId: "c1",
      sessionId: "s1",
      language: "en",
      deviceInfo: DEVICE,
      createdAt: "2026-02-15T00:00:00.000Z",
      updatedAt: "2026-02-15T00:00:00.000Z",
      turns: [
        {
          turnId: 1,
          input: { transcript: "older transcript" },
          output: {},
          timing: { audioReceivedAt: "2026-02-15T00:00:01.000Z" },
        },
        {
          turnId: 1,
          input: {},
          output: {},
          timing: { audioReceivedAt: "2026-02-15T00:00:02.000Z" },
        },
      ],
    };
    await writeFile(join(convDir, "conversation.json"), JSON.stringify(conversation, null, 2));

    await ds.updateTurn("c1", "s1", 1, { input: { transcript: "latest transcript" } });
    const raw = JSON.parse(await readFile(join(convDir, "conversation.json"), "utf-8"));
    expect(raw.turns[0].input.transcript).toBe("older transcript");
    expect(raw.turns[1].input.transcript).toBe("latest transcript");

    const conv = await ds.getConversation("c1", "s1");
    expect(conv).not.toBeNull();
    expect(conv!.turns).toHaveLength(1);
    expect(conv!.turns[0].turnId).toBe(1);
    expect(conv!.turns[0].input.transcript).toBe("latest transcript");

    const list = await ds.listConversations("c1");
    expect(list).toHaveLength(1);
    expect(list[0].id).toBe("s1");
    expect(list[0].turnCount).toBe(1);
  });
});
