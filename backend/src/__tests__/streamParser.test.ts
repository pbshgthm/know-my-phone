import { describe, it, expect } from "@jest/globals";
import { StreamingAnswerParser, stripAudioTags } from "../streamParser.js";

describe("StreamingAnswerParser", () => {
  it("should extract answer from complete JSON in one chunk", () => {
    const parser = new StreamingAnswerParser();
    const sentences = parser.feed(
      '{"answer": "Hello, this is a complete answer.", "highlights": []}'
    );
    expect(sentences).toEqual([]);
    const remaining = parser.flush();
    expect(remaining).toBe("Hello, this is a complete answer.");
  });

  it("should extract sentences from streaming chunks", () => {
    const parser = new StreamingAnswerParser();
    const allSentences: string[] = [];

    // Simulate chunked delivery
    allSentences.push(...parser.feed('{"answ'));
    allSentences.push(...parser.feed('er": "This is the first sentence. '));
    allSentences.push(...parser.feed("And here is the second sentence. "));
    allSentences.push(...parser.feed('The end is near.", "highlights": []}'));

    expect(allSentences).toEqual([
      "This is the first sentence.",
      "And here is the second sentence.",
    ]);

    const remaining = parser.flush();
    expect(remaining).toBe("The end is near.");
  });

  it("should handle escaped quotes in the answer", () => {
    const parser = new StreamingAnswerParser();
    const sentences = parser.feed(
      '{"answer": "She said \\"hello\\" to everyone.", "highlights": []}'
    );
    expect(sentences).toEqual([]);
    const remaining = parser.flush();
    expect(remaining).toBe('She said "hello" to everyone.');
  });

  it("should handle escaped backslashes", () => {
    const parser = new StreamingAnswerParser();
    parser.feed('{"answer": "Path is C:\\\\Users\\\\test.", "highlights": []}');
    const remaining = parser.flush();
    expect(remaining).toBe("Path is C:\\Users\\test.");
  });

  it("should handle newline escapes", () => {
    const parser = new StreamingAnswerParser();
    parser.feed('{"answer": "Line one.\\nLine two.", "highlights": []}');
    const remaining = parser.flush();
    expect(remaining).toBe("Line one.\nLine two.");
  });

  it("should not split short fragments (e.g. abbreviations)", () => {
    const parser = new StreamingAnswerParser();
    const sentences = parser.feed(
      '{"answer": "Dr. Smith is here. You should contact Dr. Smith at the hospital for your appointment.", "highlights": []}'
    );
    // "Dr. Smith is here." is only 18 chars, so it should stay with the next sentence
    // The split happens at the first boundary after MIN_SENTENCE_LENGTH
    // "Dr. Smith is here. You should contact Dr." -> first boundary ". " after 20 chars
    expect(sentences.length).toBeGreaterThanOrEqual(1);
    const remaining = parser.flush();
    const all = [...sentences, remaining].join(" ");
    expect(all).toContain("Dr. Smith");
  });

  it("should accumulate raw output for final JSON parse", () => {
    const parser = new StreamingAnswerParser();
    parser.feed('{"answer": "Test answer');
    parser.feed('.", "highlights": [{"elementId": "n_1", "label": "Tap"}]}');

    const raw = parser.getRawOutput();
    const parsed = JSON.parse(raw);
    expect(parsed.answer).toBe("Test answer.");
    expect(parsed.highlights).toHaveLength(1);
    expect(parsed.highlights[0].elementId).toBe("n_1");
  });

  it("should handle chunked delivery across key boundary", () => {
    const parser = new StreamingAnswerParser();
    const allSentences: string[] = [];

    // Split right in the middle of "answer"
    allSentences.push(...parser.feed('{"an'));
    allSentences.push(...parser.feed('swer"'));
    allSentences.push(...parser.feed(': "'));
    allSentences.push(...parser.feed("A short reply."));
    allSentences.push(...parser.feed('", "highlights": []}'));

    expect(allSentences).toEqual([]);
    expect(parser.flush()).toBe("A short reply.");
  });

  it("should handle multiple sentence terminators", () => {
    const parser = new StreamingAnswerParser();
    const sentences: string[] = [];

    sentences.push(
      ...parser.feed(
        '{"answer": "Is this working? Yes it is working perfectly! And that is great news. The end.", "highlights": []}'
      )
    );

    // Should extract at least some complete sentences
    const remaining = parser.flush();
    const allText = [...sentences, remaining].join(" ");
    expect(allText).toContain("Is this working?");
    expect(allText).toContain("Yes it is working perfectly!");
    expect(allText).toContain("The end.");
  });
});

describe("stripAudioTags", () => {
  it("should strip audio tags from text", () => {
    expect(stripAudioTags("[warmly] Hello there!")).toBe("Hello there!");
    expect(stripAudioTags("[cheerfully] Great news!")).toBe("Great news!");
  });

  it("should handle text without tags", () => {
    expect(stripAudioTags("Hello there!")).toBe("Hello there!");
  });

  it("should strip multiple tags", () => {
    expect(stripAudioTags("[warmly] Hello [gently] there")).toBe("Hello there");
  });
});
